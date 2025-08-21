package brew.debug.host.debughost;

import com.microsoft.java.debug.core.protocol.Types.Source;

public class DebugStackFrame extends com.microsoft.java.debug.core.protocol.Types.StackFrame {
    public String instructionPointerReference;

    public DebugStackFrame(int id, String name, Source src, int ln, int col, String presentationHint) {
        super(id, name, src, ln, col, presentationHint);
    }

    public void setInstructionPointerReference(String instructionPointerReference) {
        this.instructionPointerReference = instructionPointerReference;
    }

}
