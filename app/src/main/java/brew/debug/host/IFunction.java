package brew.debug.host;

import java.io.File;

import com.microsoft.java.debug.core.protocol.Types.Breakpoint;

public interface IFunction {

    File getFile();

    String getDisplayName();

    int getSourceLine(int functionLine);

    boolean updateBreakpoint(Breakpoint bp);
}
