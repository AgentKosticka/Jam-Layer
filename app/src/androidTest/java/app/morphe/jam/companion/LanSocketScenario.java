package app.morphe.jam.companion;

import android.app.Instrumentation;
import android.os.Bundle;
import java.net.*;
import java.util.*;

/** Direct LAN echo under the app UID; leaves pairing and the music queue untouched. */
final class LanSocketScenario {
  static void run(Instrumentation test, Bundle args) throws Exception {
    try (LocalNetworkTracker tracker = new LocalNetworkTracker(test.getTargetContext())) {
      tracker.start();
      Thread.sleep(500);
      if (tracker.snapshot().localNetworks.isEmpty()) throw new AssertionError("No physical LAN");
      if (!tracker.snapshot().vpnActive) throw new AssertionError("VPN must remain active");
      if ("lanSocketHost".equals(args.getString("role"))) {
        try (ServerSocket server = new ServerSocket(39548)) {
          server.setSoTimeout(60000);
          Bundle ready = new Bundle(); ready.putString("stream", "LAN_SOCKET_READY\n"); test.sendStatus(0, ready);
          try (Socket socket = server.accept()) {
            socket.setSoTimeout(5000);
            if (socket.getInputStream().read() != 42) throw new AssertionError("Request missing");
            socket.getOutputStream().write(43);
          }
        }
      } else {
        LanEndpoint endpoint = new LanEndpoint("test", "test", tracker.snapshot().localNetworks.get(0),
          Collections.singletonList(InetAddress.getByName(args.getString("address"))), 39548, Collections.emptyMap());
        LanConnection.Result result = LanConnection.connect(endpoint, tracker.snapshot(), 5000);
        try (Socket socket = result.socket) {
          socket.setSoTimeout(5000);
          socket.getOutputStream().write(42);
          if (socket.getInputStream().read() != 43) throw new AssertionError("Reply missing");
          Bundle info = new Bundle(); info.putString("stream", "route=" + result.route + " local=" + socket.getLocalAddress() + " remote=" + socket.getInetAddress() + "\n"); test.sendStatus(0, info);
        }
      }
    }
  }
}
