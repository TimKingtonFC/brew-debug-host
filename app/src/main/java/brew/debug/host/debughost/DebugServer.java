package brew.debug.host.debughost;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import com.microsoft.java.debug.core.adapter.ProtocolServer;

import brew.debug.host.ExecutionEngine;

public class DebugServer {
    public void start(ExecutionEngine engine) throws IOException {
        try (ServerSocket ss = new ServerSocket(8888)) {
            while (true) {
                Socket conn = ss.accept();
                ProtocolServer ps = new ProtocolServer(conn.getInputStream(), conn.getOutputStream(),
                        aps -> new DebugAdapter(aps, engine));
                ps.run();
            }
        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }

    }
}
