package brew.debug.host.debughost;

import java.util.List;

import com.microsoft.java.debug.core.adapter.IDebugAdapter;
import com.microsoft.java.debug.core.protocol.Events.OutputEvent.Category;
import com.microsoft.java.debug.core.protocol.Types.Breakpoint;

public interface IVMDebugAdapter extends IDebugAdapter {
    void breakpointsUpdated(List<Breakpoint> bps);

    void stopped(String reason);

    void exited();

    void output(Category category, String msg);
}
