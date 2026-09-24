package app.morphe.jam.companion;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Discovers Jams in either direction across a hotspot when mDNS is unavailable. */
final class LanProbe {

  private static final int PORT = 39547;
  private static final String MAGIC = "MJP1";
  private static final Object lock = new Object();
  private static final Map<Long, Service> services = new HashMap<>();
  private static long nextId;
  private static DatagramSocket server;

  private static final class Service {

    final String type, name, jam;
    final int port;

    Service(String type, String name, String jam, int port) {
      this.type = type;
      this.name = name;
      this.jam = jam;
      this.port = port;
    }
  }

  private LanProbe() {}

  static AutoCloseable advertise(
    LocalNetworkTracker tracker,
    String type,
    String name,
    int port,
    Map<String, byte[]> attributes
  ) {
    byte[] id = attributes.get("jam");
    if (id == null || port < 1 || port > 65535) return () -> {};
    String jam = new String(id, StandardCharsets.UTF_8);
    try {
      if (!UUID.fromString(jam).toString().equals(jam)) return () -> {};
    } catch (IllegalArgumentException invalid) {
      return () -> {};
    }
    final Service service = new Service(type, name, jam, port);
    final long token;
    synchronized (lock) {
      if (server == null) {
        try {
          DatagramSocket socket = new DatagramSocket(PORT);
          server = socket;
          Thread thread = new Thread(() -> serve(socket), "Jam LAN gateway probe");
          thread.setDaemon(true);
          thread.start();
        } catch (Exception error) {
          Log.w("MorpheJam", "LAN gateway probe unavailable", error);
          return () -> {};
        }
      }
      token = ++nextId;
      services.put(token, service);
    }
    Announcer announcer = new Announcer(tracker, service);
    announcer.start();
    return () -> {
      announcer.close();
      synchronized (lock) {
        services.remove(token);
        if (services.isEmpty() && server != null) {
          server.close();
          server = null;
        }
      }
    };
  }

  private static void serve(DatagramSocket socket) {
    byte[] input = new byte[128];
    while (!socket.isClosed()) {
      try {
        DatagramPacket request = new DatagramPacket(input, input.length);
        socket.receive(request);
        String query = new String(
          request.getData(),
          request.getOffset(),
          request.getLength(),
          StandardCharsets.UTF_8
        );
        if (!query.startsWith(MAGIC + " ")) continue;
        String type = query.substring((MAGIC + " ").length());
        Service[] matching;
        synchronized (lock) {
          matching = services.values().stream()
            .filter(value -> value.type.equals(type))
            .toArray(Service[]::new);
        }
        for (Service service : matching) {
          byte[] reply = encode(service);
          socket.send(
            new DatagramPacket(
              reply,
              reply.length,
              request.getAddress(),
              request.getPort()
            )
          );
        }
      } catch (Exception error) {
        if (!socket.isClosed()) Log.w("MorpheJam", "LAN gateway probe failed", error);
      }
    }
  }

  private static byte[] encode(Service service) {
    return (
      MAGIC +
      " " +
      service.type +
      " " +
      service.name +
      " " +
      service.port +
      " " +
      service.jam
    ).getBytes(StandardCharsets.UTF_8);
  }

  /** A client hosting a Jam announces to its gateway, which may be the guest. */
  private static final class Announcer implements AutoCloseable {

    private final LocalNetworkTracker tracker;
    private final Service service;
    private final ConnectivityManager connectivity;
    private volatile boolean closed;
    private Thread thread;

    Announcer(LocalNetworkTracker tracker, Service service) {
      this.tracker = tracker;
      this.service = service;
      connectivity = tracker.connectivity();
    }

    void start() {
      if (connectivity == null) return;
      thread = new Thread(this::announce, "Jam LAN gateway announce");
      thread.setDaemon(true);
      thread.start();
    }

    private void announce() {
      byte[] reply = encode(service);
      while (!closed) {
        for (Network network : tracker.snapshot().localNetworks) {
          LinkProperties properties;
          try {
            properties = connectivity.getLinkProperties(network);
          } catch (RuntimeException unavailable) {
            continue;
          }
          if (properties == null) continue;
          for (RouteInfo route : properties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (
              route.isDefaultRoute() &&
              gateway instanceof Inet4Address &&
              !gateway.isAnyLocalAddress()
            ) try (DatagramSocket socket = new DatagramSocket()) {
              network.bindSocket(socket);
              socket.send(new DatagramPacket(reply, reply.length, gateway, PORT));
            } catch (Exception error) {
              if (!closed) Log.d("MorpheJam", "LAN gateway announce skipped", error);
            }
          }
        }
        try {
          Thread.sleep(2000);
        } catch (InterruptedException stopped) {
          break;
        }
      }
    }

    @Override
    public void close() {
      closed = true;
      if (thread != null) thread.interrupt();
    }
  }

  static final class Browser implements AutoCloseable {

    interface Listener {
      void onEndpoint(LanEndpoint endpoint);
    }

    private final ConnectivityManager connectivity;
    private final LocalNetworkTracker tracker;
    private final String type;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private Thread thread;
    private Thread listenerThread;
    private volatile DatagramSocket listenSocket;

    Browser(
      Context context,
      LocalNetworkTracker tracker,
      String type,
      Listener listener
    ) {
      connectivity = context.getApplicationContext().getSystemService(
        ConnectivityManager.class
      );
      this.tracker = tracker;
      this.type = type;
      this.listener = listener;
    }

    void start() {
      if (closed || connectivity == null || thread != null) return;
      thread = new Thread(this::browse, "Jam LAN gateway browse");
      thread.setDaemon(true);
      thread.start();
      listenerThread = new Thread(this::listen, "Jam LAN gateway listen");
      listenerThread.setDaemon(true);
      listenerThread.start();
    }

    private void browse() {
      while (!closed) {
        for (Network network : tracker.snapshot().localNetworks) {
          if (closed) break;
          LinkProperties properties;
          try {
            properties = connectivity.getLinkProperties(network);
          } catch (RuntimeException unavailable) {
            continue;
          }
          if (properties == null) continue;
          for (RouteInfo route : properties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (
              route.isDefaultRoute() &&
              gateway instanceof Inet4Address &&
              !gateway.isAnyLocalAddress()
            ) probe(network, gateway);
          }
        }
        try {
          Thread.sleep(1500);
        } catch (InterruptedException stopped) {
          break;
        }
      }
    }

    private void probe(Network network, InetAddress gateway) {
      try (DatagramSocket socket = new DatagramSocket()) {
        network.bindSocket(socket);
        socket.setSoTimeout(700);
        byte[] query = (MAGIC + " " + type).getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(query, query.length, gateway, PORT));
        while (!closed) {
          byte[] buffer = new byte[256];
          DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
          socket.receive(reply);
          if (!gateway.equals(reply.getAddress())) continue;
          deliver(reply, network);
        }
      } catch (java.net.SocketTimeoutException done) {
        // The next probe also catches hosts that started after discovery.
      } catch (Exception error) {
        if (!closed) Log.d("MorpheJam", "LAN gateway probe skipped", error);
      }
    }

    private void listen() {
      while (!closed) try (DatagramSocket socket = new DatagramSocket(null)) {
        // Publish before binding so close() can also cancel a starting listener.
        listenSocket = socket;
        if (closed) return;
        socket.bind(new InetSocketAddress(PORT));
        socket.setSoTimeout(1000);
        while (!closed) {
          byte[] buffer = new byte[256];
          DatagramPacket announcement = new DatagramPacket(buffer, buffer.length);
          try {
            socket.receive(announcement);
            if (onLocalNetwork(announcement.getAddress()))
              deliver(announcement, null);
          } catch (java.net.SocketTimeoutException ignored) {}
        }
      } catch (java.net.BindException busy) {
        // Pairing and session discovery can briefly overlap during handoff.
        // Retry until the previous listener releases the shared discovery port.
        try {
          Thread.sleep(200);
        } catch (InterruptedException stopped) {
          return;
        }
      } catch (Exception error) {
        if (!closed) Log.d("MorpheJam", "LAN gateway listener unavailable", error);
        return;
      } finally {
        listenSocket = null;
      }
    }

    private boolean onLocalNetwork(InetAddress source) {
      if (!(source instanceof Inet4Address)) return false;
      int remote = ipv4(source);
      for (Network network : tracker.snapshot().localNetworks) {
        LinkProperties properties;
        try {
          properties = connectivity.getLinkProperties(network);
        } catch (RuntimeException unavailable) {
          continue;
        }
        if (properties == null) continue;
        for (LinkAddress local : properties.getLinkAddresses()) {
          if (!(local.getAddress() instanceof Inet4Address)) continue;
          int bits = local.getPrefixLength();
          int mask = bits == 0 ? 0 : -1 << (32 - bits);
          if (
            !source.equals(local.getAddress()) &&
            (remote & mask) == (ipv4(local.getAddress()) & mask)
          ) return true;
        }
      }
      return false;
    }

    private int ipv4(InetAddress address) {
      byte[] bytes = address.getAddress();
      return (
        ((bytes[0] & 255) << 24) |
        ((bytes[1] & 255) << 16) |
        ((bytes[2] & 255) << 8) |
        (bytes[3] & 255)
      );
    }

    private void deliver(DatagramPacket packet, Network network) {
      try {
        String value = new String(
          packet.getData(),
          packet.getOffset(),
          packet.getLength(),
          StandardCharsets.UTF_8
        );
        String[] fields = value.split(" ");
        if (
          fields.length != 5 ||
          !MAGIC.equals(fields[0]) ||
          !type.equals(fields[1])
        ) return;
        int port = Integer.parseInt(fields[3]);
        String jam = UUID.fromString(fields[4]).toString();
        if (port < 1 || port > 65535 || !jam.equals(fields[4])) return;
        Map<String, byte[]> attrs = new HashMap<>();
        attrs.put("jam", jam.getBytes(StandardCharsets.UTF_8));
        LanEndpoint endpoint = new LanEndpoint(
          fields[2],
          type,
          network,
          Collections.singletonList(packet.getAddress()),
          port,
          attrs
        );
        handler.post(() -> {
          if (!closed) listener.onEndpoint(endpoint);
        });
      } catch (RuntimeException malformed) {
        // Ignore unrelated UDP traffic on the probe port.
      }
    }

    @Override
    public void close() {
      closed = true;
      if (thread != null) thread.interrupt();
      if (listenerThread != null) listenerThread.interrupt();
      DatagramSocket socket = listenSocket;
      if (socket != null) socket.close();
    }
  }
}
