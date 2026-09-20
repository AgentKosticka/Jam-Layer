package app.morphe.jam.companion;

import android.net.Network;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import java.io.IOException;
import java.net.*;
import java.util.*;
import javax.net.SocketFactory;

/** Use the network that discovered the service, including when Wi-Fi is not default. */
final class LanConnection {
    private LanConnection() {}

    static Socket connect(NsdServiceInfo service, int timeoutMillis) throws IOException {
        Network network = Build.VERSION.SDK_INT >= 33 ? service.getNetwork() : null;
        List<InetAddress> addresses = Build.VERSION.SDK_INT >= 34
                ? service.getHostAddresses() : Collections.singletonList(service.getHost());
        SocketFactory factory = network == null ? SocketFactory.getDefault() : network.getSocketFactory();
        return connect(addresses, service.getPort(), timeoutMillis, factory);
    }

    static Socket connect(List<InetAddress> addresses, int port, int timeoutMillis,
                          SocketFactory factory) throws IOException {
        IOException failure = new IOException("LAN service has no reachable address");
        for (InetAddress address : addresses) {
            if (address == null) continue;
            Socket socket = factory.createSocket();
            try {
                socket.connect(new InetSocketAddress(address, port), timeoutMillis);
                return socket;
            } catch (IOException error) {
                failure.addSuppressed(error);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
        throw failure;
    }
}
