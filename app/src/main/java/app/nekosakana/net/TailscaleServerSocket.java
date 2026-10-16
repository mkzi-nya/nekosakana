package app.nekosakana.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import app.nekosakana.App;
import app.nekosakana.core.Config;

/** ServerSocket adapter whose listener lives entirely inside the app netstack. */
final class TailscaleServerSocket extends ServerSocket {
    private final libtailscale.TCPListener listener;
    private final int port;

    TailscaleServerSocket(int port) throws IOException {
        super();
        try {
            String address = Config.selfAddress();
            if (address.isEmpty()) throw new IOException("Tailscale address not ready");
            listener = App.get().tailscale().listenTCP(address + ":" + port);
            String bound = listener.address();
            this.port = Integer.parseInt(bound.substring(bound.lastIndexOf(':') + 1));
        } catch (Exception e) {
            throw new IOException("Tailscale listen failed", e);
        }
    }

    @Override public Socket accept() throws IOException {
        try { return new TailscaleSocket(listener.accept(), "", port); }
        catch (Exception e) { throw new IOException("Tailscale accept failed", e); }
    }

    @Override public int getLocalPort() { return port; }

    @Override public void close() throws IOException {
        listener.close();
        super.close();
    }
}
