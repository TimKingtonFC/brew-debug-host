package brew.debug.host;

import java.util.List;
import java.util.Stack;

import com.microsoft.java.debug.core.protocol.Requests.DisassembleArguments;
import com.microsoft.java.debug.core.protocol.Requests.DisassembledInstruction;
import com.microsoft.java.debug.core.protocol.Requests.ReadMemoryArguments;
import com.microsoft.java.debug.core.protocol.Requests.WriteMemoryArguments;
import com.microsoft.java.debug.core.protocol.Responses.ReadMemoryResponse;
import com.microsoft.java.debug.core.protocol.Responses.WriteMemoryResponse;
import com.microsoft.java.debug.core.protocol.Types.Scope;
import com.microsoft.java.debug.core.protocol.Types.Variable;

public interface IThread {
    boolean step();

    boolean atFirstInstructionOnLine();

    Stack<? extends IStackFrame> getDebugCallStack();

    List<Scope> getScopes(int frameId);

    List<Variable> getVariables(int varRef);

    DisassembledInstruction[] disassembleInstructions(DisassembleArguments da);

    ReadMemoryResponse readMemory(ReadMemoryArguments rma);

    WriteMemoryResponse writeMemory(WriteMemoryArguments wma);
}
