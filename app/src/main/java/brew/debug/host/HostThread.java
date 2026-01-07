package brew.debug.host;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import com.microsoft.java.debug.core.protocol.Events.OutputEvent.Category;
import com.microsoft.java.debug.core.protocol.Requests.DisassembleArguments;
import com.microsoft.java.debug.core.protocol.Requests.DisassembledInstruction;
import com.microsoft.java.debug.core.protocol.Requests.ReadMemoryArguments;
import com.microsoft.java.debug.core.protocol.Requests.SetBreakpointArguments;
import com.microsoft.java.debug.core.protocol.Requests.SteppingGranularity;
import com.microsoft.java.debug.core.protocol.Requests.WriteMemoryArguments;
import com.microsoft.java.debug.core.protocol.Responses.ReadMemoryResponse;
import com.microsoft.java.debug.core.protocol.Responses.WriteMemoryResponse;
import com.microsoft.java.debug.core.protocol.Types.Breakpoint;
import com.microsoft.java.debug.core.protocol.Types.InstructionBreakpoint;
import com.microsoft.java.debug.core.protocol.Types.Scope;
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint;
import com.microsoft.java.debug.core.protocol.Types.Variable;

import brew.debug.host.debughost.DebugAdapter;
import brew.debug.host.debughost.IVMDebugAdapter;

public class HostThread extends Thread {
    private enum State {
        Running,
        Stepping,
        Suspended,
        Terminated
    }

    private static final int NO_STOP_FRAME = -1;

    public static String formatAddress(int address) {
        return String.format("0x%08x", address);
    }

    private IVMDebugAdapter da;
    private ExecutionEngine executionEngine;
    private File file;
    private IThread thread;
    private volatile State state;
    private volatile SteppingGranularity steppingGranularity;
    private Map<File, Map<Integer, Breakpoint>> breakpointsByFile = new HashMap<>();
    private Set<Integer> instructionBreakpoints = new HashSet<>();
    private int maxStopFrame;
    private int nextBreakpointId;
    private boolean stopOnEntry;

    public HostThread(IVMDebugAdapter da, ExecutionEngine executionEngine) {
        this.da = da;
        this.executionEngine = executionEngine;
        this.state = State.Running;
        this.maxStopFrame = NO_STOP_FRAME;
    }

    public void stopOnEntry() {
        stopOnEntry = true;
    }

    public void compileCode(File file) {
        executionEngine.compileFile(file);
        this.file = file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public void verifyBreakpoints() {
        List<Breakpoint> updatedBreakpoints = new ArrayList<>();
        for (var entry : breakpointsByFile.entrySet()) {
            File file = entry.getKey();
            var breakpointsByLine = entry.getValue();

            List<Breakpoint> movedBreakpoints = new ArrayList<>();
            for (var iter = breakpointsByLine.values().iterator(); iter.hasNext();) {
                Breakpoint br = iter.next();
                IFunction function;
                try {
                    function = executionEngine.resolveFunction(file, br.line - 1);
                } catch (Exception e) {
                    function = null;
                }
                if (function == null) {
                    if (br.verified) {
                        br.verified = false;
                        updatedBreakpoints.add(br);
                        iter.remove();
                    }
                    continue;
                }

                int oldLine = br.line;
                if (function.updateBreakpoint(br)) {
                    updatedBreakpoints.add(br);
                    if (!br.verified) {
                        iter.remove();
                    }
                    if (br.line != oldLine) {
                        movedBreakpoints.add(br);
                    }
                }
            }

            for (Breakpoint br : movedBreakpoints) {
                // TODO: Is old entry still on old line?
                breakpointsByLine.put(br.line, br);
            }
        }

        if (!updatedBreakpoints.isEmpty()) {
            da.breakpointsUpdated(updatedBreakpoints);
        }
    }

    @Override
    public void run() {
        // TODO: List breakpoints, enable/disable, etc.
        // TODO: Hover for var value - what about other stack frames?
        // TODO: Handle errors - no function def, for example
        String entryPoint = file.getName().endsWith(".s") ? "main" : "Main.main";
        thread = executionEngine.startThread(file, entryPoint, msg -> da.output(Category.stdout, msg + "\n"));
        if (stopOnEntry) {
            stop("entry");
        } else {
            stopIfNecessary();
        }

        synchronized (this) {
            while (true) {
                switch (state) {
                    case Running:
                    case Stepping:
                        // TODO: Break tight loop
                        try {
                            if (!thread.step()) {
                                da.exited();
                                return;
                            }
                        } catch (VMException e) {
                            da.output(Category.stderr, e.getMessage());
                            da.exited();
                            return;
                        } catch (Exception e) {
                            da.output(Category.stderr, DebugAdapter.getStackTrace(e));
                            da.exited();
                            return;
                        }

                        stopIfNecessary();
                        break;
                    case Suspended:
                        try {
                            wait(1000);
                        } catch (InterruptedException e) {
                            // Expected
                        }
                        break;
                    case Terminated:
                        return;
                }
            }
        }
    }

    private void stopIfNecessary() {
        Stack<? extends IStackFrame> callStack = thread.getDebugCallStack();
        IStackFrame frame = callStack.peek();
        IFunction function = frame.getFunction();
        File filename = function.getFile();

        if (!executionEngine.isInternalFile(filename)) {
            if (breakpointsByFile
                    .computeIfAbsent(filename, k -> new HashMap<>())
                    .get(function.getSourceLine(frame.getProgramCounter())) != null &&
                    thread.atFirstInstructionOnLine()) {
                stop("breakpoint");
            } else if (instructionBreakpoints.contains(frame.getProgramCounterAddress())) {
                stop("instruction breakpoint");
            }
            if (state == State.Stepping) {
                if (maxStopFrame == NO_STOP_FRAME || callStack.size() <= maxStopFrame) {
                    if (steppingGranularity == SteppingGranularity.INSTRUCTION ||
                            thread.atFirstInstructionOnLine()) {
                        stop("step");
                    }
                }
            }
        }
    }

    private void stop(String reason) {
        state = State.Suspended;
        maxStopFrame = NO_STOP_FRAME;
        da.stopped(reason);
    }

    public synchronized void next(SteppingGranularity granularity) {
        state = State.Stepping;
        steppingGranularity = granularity;
        maxStopFrame = thread.getDebugCallStack().size();
        notify();
    }

    public synchronized void stepIn(SteppingGranularity granularity) {
        state = State.Stepping;
        steppingGranularity = granularity;
        notify();
    }

    public synchronized void stepOut(SteppingGranularity granularity) {
        state = State.Stepping;
        steppingGranularity = granularity;
        maxStopFrame = thread.getDebugCallStack().size() - 1;
        notify();
    }

    public synchronized void continueExecution() {
        state = State.Running;
        notify();
    }

    public synchronized List<Breakpoint> setBreakpoints(SetBreakpointArguments sba) {
        var breakpointsByLine = breakpointsByFile.computeIfAbsent(new File(sba.source.path), k -> new HashMap<>());
        breakpointsByLine.clear();

        List<Breakpoint> bpResults = new ArrayList<>();
        for (SourceBreakpoint sbp : sba.breakpoints) {
            Breakpoint bp = new Breakpoint(nextBreakpointId++, false, sbp.line, null);
            breakpointsByLine.put(bp.line, bp);
            bpResults.add(bp);
        }
        return bpResults;
    }

    public synchronized List<Breakpoint> setInstructionBreakpoints(InstructionBreakpoint[] breakpoints) {
        instructionBreakpoints.clear();
        List<Breakpoint> responseBreakpoints = new ArrayList<>();

        for (InstructionBreakpoint ib : breakpoints) {
            if (!ib.instructionReference.startsWith("0x")) {
                throw new RuntimeException("Bad instruction reference format (must be a hex number, e.g. 0x40000): "
                        + ib.instructionReference);
            }

            int address = Integer.parseInt(ib.instructionReference.substring(2), 16) + ib.offset;
            instructionBreakpoints.add(address);

            Breakpoint bp = new Breakpoint(address, true, ib.instructionReference, ib.offset);
            responseBreakpoints.add(bp);
        }
        return responseBreakpoints;
    }

    public Stack<? extends IStackFrame> getCallStack() {
        return thread.getDebugCallStack();
    }

    public List<Scope> getScopes(int frameId) {
        return thread.getScopes(frameId);
    }

    public List<Variable> getVariables(int varRef) {
        return thread.getVariables(varRef);
    }

    public DisassembledInstruction[] disassembleInstructions(DisassembleArguments dis) {
        return thread.disassembleInstructions(dis);
    }

    public ReadMemoryResponse readMemory(ReadMemoryArguments rma) {
        return thread.readMemory(rma);
    }

    public WriteMemoryResponse writeMemory(WriteMemoryArguments wma) {
        return thread.writeMemory(wma);
    }

    public synchronized void terminate() {
        state = State.Terminated;
        notify();
        try {
            join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean hasTerminated() {
        return state == State.Terminated;
    }
}
