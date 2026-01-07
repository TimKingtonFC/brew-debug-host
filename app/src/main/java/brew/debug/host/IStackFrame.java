package brew.debug.host;

public interface IStackFrame {
    IFunction getFunction();

    int getProgramCounter();

    int getProgramCounterAddress();

    String getProgramCounterReference();
}
