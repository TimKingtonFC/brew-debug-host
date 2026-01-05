package brew.debug.host.debughost;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;

import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.ErrorCode;
import com.microsoft.java.debug.core.protocol.Events;
import com.microsoft.java.debug.core.protocol.Events.OutputEvent;
import com.microsoft.java.debug.core.protocol.Events.OutputEvent.Category;
import com.microsoft.java.debug.core.protocol.Events.TerminatedEvent;
import com.microsoft.java.debug.core.protocol.IProtocolServer;
import com.microsoft.java.debug.core.protocol.JsonUtils;
import com.microsoft.java.debug.core.protocol.Messages;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.Command;
import com.microsoft.java.debug.core.protocol.Requests.DisassembleArguments;
import com.microsoft.java.debug.core.protocol.Requests.DisassembledInstruction;
import com.microsoft.java.debug.core.protocol.Requests.DisconnectArguments;
import com.microsoft.java.debug.core.protocol.Requests.LaunchArguments;
import com.microsoft.java.debug.core.protocol.Requests.NextArguments;
import com.microsoft.java.debug.core.protocol.Requests.ReadMemoryArguments;
import com.microsoft.java.debug.core.protocol.Requests.ScopesArguments;
import com.microsoft.java.debug.core.protocol.Requests.SetBreakpointArguments;
import com.microsoft.java.debug.core.protocol.Requests.SetInstructionBreakpointsArguments;
import com.microsoft.java.debug.core.protocol.Requests.StackTraceArguments;
import com.microsoft.java.debug.core.protocol.Requests.StepInArguments;
import com.microsoft.java.debug.core.protocol.Requests.StepOutArguments;
import com.microsoft.java.debug.core.protocol.Requests.VariablesArguments;
import com.microsoft.java.debug.core.protocol.Requests.WriteMemoryArguments;
import com.microsoft.java.debug.core.protocol.Responses;
import com.microsoft.java.debug.core.protocol.Responses.StackTraceResponseBody;
import com.microsoft.java.debug.core.protocol.Types;
import com.microsoft.java.debug.core.protocol.Types.Breakpoint;
import com.microsoft.java.debug.core.protocol.Types.Capabilities;
import com.microsoft.java.debug.core.protocol.Types.InstructionBreakpoint;
import com.microsoft.java.debug.core.protocol.Types.Source;

import brew.debug.host.ExecutionEngine;
import brew.debug.host.HostThread;
import brew.debug.host.IFunction;
import brew.debug.host.IStackFrame;

public class DebugAdapter implements IVMDebugAdapter {

    private IProtocolServer ps;
    private HostThread hostThread;
    private boolean launched;
    private String workspaceRoot;

    public DebugAdapter() {
    }

    public DebugAdapter(IProtocolServer ps, ExecutionEngine engine) {
        this.ps = ps;
        hostThread = new HostThread(this, engine);
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public CompletableFuture<Messages.Response> dispatchRequest(Messages.Request request) {
        Messages.Response response = new Messages.Response();
        response.request_seq = request.seq;
        response.command = request.command;
        response.success = true;

        Command command = Command.parse(request.command);
        Arguments cmdArgs = JsonUtils.fromJson(request.arguments, command.getArgumentType());
        List<Types.Thread> threads = List.of(new Types.Thread(1, "main"));

        if (hostThread.hasTerminated() && command != Command.DISCONNECT) {
            return CompletableFuture.completedFuture(response);
        }
        // TODO: Read whole spec
        // TODO: Launch java
        System.out.println("Command: " + command + " " + request.command);
        switch (command) {
            case INITIALIZE -> {
                // This is because of a bug in https://github.com/microsoft/java-debug
                // This should be set to an InitializeResponseBody, but then the JSON is generated incorrectly.
                response.body = getCapabilities();
                ps.sendEvent(new Events.InitializedEvent());
            }
            case CONFIGURATIONDONE -> {
            }
            case LAUNCH -> {
                // TODO: Errors on bad lifecycle events
                var la = (LaunchArguments) cmdArgs;
                workspaceRoot = la.cwd;

                try {
                    hostThread.compileCode(new File(la.projectName));
                } catch (Exception e) {
                    e.printStackTrace();

                    ps.sendEvent(new OutputEvent(Category.stderr, getStackTrace(e)));
                    ps.sendEvent(new TerminatedEvent());
                    return CompletableFuture.completedFuture(response);
                }

                hostThread.verifyBreakpoints();
                if (la.stopOnEntry) {
                    hostThread.stopOnEntry();
                }
                new Thread(hostThread).start();
                launched = true;
            }
            case THREADS ->
                response.body = new Responses.ThreadsResponseBody(threads);
            case STACKTRACE -> {
                response.body = getStackFrames((StackTraceArguments) cmdArgs);
            }
            case SCOPES -> {
                var sca = (ScopesArguments) cmdArgs;
                response.body = new Responses.ScopesResponseBody(hostThread.getScopes(sca.frameId));
            }
            case VARIABLES -> {
                var varArgs = (VariablesArguments) cmdArgs;
                response.body = new Responses.VariablesResponseBody(
                        hostThread.getVariables(varArgs.variablesReference));
            }
            // case EVALUATE -> {
            //     var evArgs = (EvaluateArguments) cmdArgs;

            // }
            case SETBREAKPOINTS -> {
                var bpts = hostThread.setBreakpoints((SetBreakpointArguments) cmdArgs);
                response.body = new Responses.SetBreakpointsResponseBody(bpts);

                if (launched) {
                    hostThread.verifyBreakpoints();
                }
            }
            case SETINSTRUCTIONBREAKPOINTS -> {
                InstructionBreakpoint[] breakpoints = ((SetInstructionBreakpointsArguments) cmdArgs).breakpoints;
                response.body = new Responses.SetInstructionBreakpointsResponseBody(
                        hostThread.setInstructionBreakpoints(breakpoints));
            }
            // case SETEXCEPTIONBREAKPOINTS -> {
            //     var ebpts = (SetExceptionBreakpointsArguments) cmdArgs;
            //     if (ebpts.filters.length > 0) {
            //         throw new UnsupportedOperationException();
            //     }
            // }
            case DISASSEMBLE -> {
                DisassembledInstruction[] instructions = hostThread
                        .disassembleInstructions((DisassembleArguments) cmdArgs);
                response.body = new Responses.DisassembleResponse(instructions);
            }
            case READMEMORY -> response.body = hostThread.readMemory((ReadMemoryArguments) cmdArgs);
            case WRITEMEMORY -> response.body = hostThread.writeMemory((WriteMemoryArguments) cmdArgs);
            case CONTINUE ->
                hostThread.continueExecution();
            case NEXT ->
                hostThread.next(((NextArguments) cmdArgs).granularity);
            case STEPIN ->
                hostThread.stepIn(((StepInArguments) cmdArgs).granularity);
            case STEPOUT ->
                hostThread.stepOut(((StepOutArguments) cmdArgs).granularity);
            case SOURCE ->
                throw new RuntimeException("Source request - path mismatch?");
            case DISCONNECT -> {
                DisconnectArguments da = (DisconnectArguments) cmdArgs;
                if (da.terminateDebuggee) {
                    hostThread.terminate();
                }
                ps.sendEvent(new Events.TerminatedEvent());
            }
            default -> {
                System.out.println("Unsupported command: " + command);
                final String errorMessage = String.format("Unrecognized request: { _request: %s }", request.command);
                return AdapterUtils.createAsyncErrorResponse(response, ErrorCode.UNRECOGNIZED_REQUEST_FAILURE,
                        errorMessage);
            }
        }

        return CompletableFuture.completedFuture(response);
    }

    private Capabilities getCapabilities() {
        var cap = new Capabilities();
        cap.supportTerminateDebuggee = true;
        cap.supportsDelayedStackTraceLoading = true;
        cap.supportsRestartRequest = true;
        // cap.supportsSetVariable = true;
        // cap.supportsFunctionBreakpoints = true;
        cap.supportsEvaluateForHovers = true;
        // cap.supportsConditionalBreakpoints = true;
        // cap.supportsHitConditionalBreakpoints = true;
        // cap.supportsDataBreakpoints = true;
        // cap.supportsRestartFrame = true;
        // cap.supportsLogPoints = true;
        cap.supportsReadMemoryRequest = true;
        cap.supportsWriteMemoryRequest = true;
        cap.supportsDisassembleRequest = true;
        cap.supportsSteppingGranularity = true;
        cap.supportsInstructionBreakpoints = true;
        return cap;
    }

    public StackTraceResponseBody getStackFrames(StackTraceArguments args) {
        Stack<? extends IStackFrame> callStack = hostThread.getCallStack();
        List<Types.StackFrame> frames = new ArrayList<>();

        int startIndex = callStack.size() - 1 - args.startFrame;
        int stopIndex = Math.max(0, callStack.size() - 1 - args.levels);
        for (int i = startIndex; i >= stopIndex; i--) {
            IStackFrame vmFrame = callStack.get(i);
            IFunction function = vmFrame.getFunction();

            DebugStackFrame frame = new DebugStackFrame(i + 1, function.getDisplayName(),
                    new Source(function.getFile().getName(), function.getFile().getAbsolutePath(), 0),
                    function.getSourceLine(vmFrame.getProgramCounter()), 0, null);

            String instruction = vmFrame.getInstructionPointerReference();
            if (!instruction.startsWith("0x")) {
                throw new RuntimeException(
                        "Bad instruction reference format (must be a hex number, e.g. 0x40000): " + instruction);
            }
            if (instruction != null) {
                frame.setInstructionPointerReference(instruction);
            }

            frames.add(frame);
        }
        return new StackTraceResponseBody(frames, callStack.size());
    }

    @Override
    public void breakpointsUpdated(List<Breakpoint> bps) {
        for (Breakpoint bp : bps) {
            ps.sendEvent(new Events.BreakpointEvent("changed", bp));
        }
    }

    @Override
    public void stopped(String reason) {
        ps.sendEvent(new Events.StoppedEvent(reason, 1));
        ps.sendEvent(new Events.DebugEvent("customStopped"));
    }

    @Override
    public void exited() {
        ps.sendEvent(new Events.TerminatedEvent());
        ps.sendEvent(new Events.ExitedEvent(0));
    }

    @Override
    public void output(Category category, String msg) {
        ps.sendEvent(new OutputEvent(category, msg));
    }

    public static String getStackTrace(Throwable e) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        e.printStackTrace(printWriter);
        return stringWriter.toString();
    }
}
// TODO: Extenison icons
// TODO: extension hover, etc
// TODO: Show binary data
