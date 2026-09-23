package app.morphe.jam.companion;

import android.content.Context;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** LAN and Wi-Fi Aware bootstrap. Successful PAKE delivers the full invitation. */
public final class CodePairing implements AutoCloseable {

  private static final String TYPE = "_morphepair._tcp.";

  interface HandoffListener {
    /** Returns true only after ownership of the connection has transferred. */
    boolean onHandoff(Handoff handoff);
  }

  /** A PAKE-authenticated connection that can continue as the Jam channel. */
  static final class Handoff implements AutoCloseable {

    final String invitation, transport, route;
    private Socket socket;
    private AutoCloseable path;

    Handoff(
      String invitation,
      Socket socket,
      String transport,
      String route,
      AutoCloseable path
    ) {
      this.invitation = invitation;
      this.socket = socket;
      this.transport = transport;
      this.route = route;
      this.path = path;
    }

    synchronized ChannelTransport takeConnection() throws java.io.IOException {
      if (socket == null) throw new java.io.IOException("Pairing connection closed");
      Socket value = socket;
      AutoCloseable resource = path;
      socket = null;
      path = null;
      try {
        return new SocketChannelTransport(value, resource);
      } catch (java.io.IOException error) {
        close(value, resource);
        throw error;
      }
    }

    @Override
    public synchronized void close() {
      Socket value = socket;
      AutoCloseable resource = path;
      socket = null;
      path = null;
      close(value, resource);
    }

    private static void close(Socket socket, AutoCloseable path) {
      if (socket != null) try {
        socket.close();
      } catch (Exception ignored) {}
      if (path != null) try {
        path.close();
      } catch (Exception ignored) {}
    }
  }

  private final Context context;
  private final LocalNetworkTracker topology;
  private final LanAdvertiser advertiser;
  private final ServerSocket server;
  private final Invitation invite;
  private final String code = CodeExchange.generate();
  private final long expires = System.currentTimeMillis() + 10 * 60 * 1000L;
  private final ExecutorService workers = Executors.newFixedThreadPool(3);
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final HandoffListener handoffListener;
  private AwareCodePairing aware;
  private volatile boolean closed;

  public CodePairing(Context context, Invitation invitation) throws Exception {
    this(context, invitation, null);
  }

  CodePairing(
    Context context,
    Invitation invitation,
    HandoffListener handoffListener
  ) throws Exception {
    this.context = context.getApplicationContext();
    invite = invitation;
    this.handoffListener = handoffListener;
    server = new ServerSocket(0);
    topology = new LocalNetworkTracker(this.context);
    topology.start();
    Map<String, byte[]> attrs = new HashMap<>();
    attrs.put("jam", invite.jamId.getBytes(StandardCharsets.UTF_8));
    attrs.put("v", "1".getBytes(StandardCharsets.UTF_8));
    advertiser = new LanAdvertiser(
      this.context,
      topology,
      TYPE,
      "Jam-pair-" + invite.jamId.substring(0, 8),
      server.getLocalPort(),
      attrs,
      status -> android.util.Log.i("MorpheJam", "Code pairing " + status)
    );
    advertiser.start();
    aware = AwareCodePairing.host(
      this.context,
      invite,
      code,
      server.getLocalPort()
    );
    workers.execute(() -> {
      int attempts = 0,
        inWindow = 0;
      long window = System.currentTimeMillis();
      while (!closed) {
        try {
          Socket socket = server.accept();
          long now = System.currentTimeMillis();
          if (now - window > 60000) {
            window = now;
            inWindow = 0;
          }
          if (
            !valid() || attempts >= 32 || inWindow >= 8 || sockets.size() >= 2
          ) {
            socket.close();
            continue;
          }
          attempts++;
          inWindow++;
          sockets.add(socket);
          workers.execute(() -> {
            boolean handedOff = false;
            AwareDataPath path = null;
            try {
              CodeExchange.giveAndKeep(socket, invite, code);
              AwareCodePairing pairing = aware;
              if (pairing != null) path = pairing.takeReadyPath(socket);
              Handoff handoff = new Handoff(
                null,
                socket,
                path == null ? "LAN" : "Aware",
                path == null ? "SYSTEM_ROUTED_FALLBACK" : "AWARE_NETWORK",
                path
              );
              if (handoffListener != null) handedOff = handoffListener.onHandoff(
                handoff
              );
              if (!handedOff) handoff.close();
            } catch (Exception ignored) {
            } finally {
              sockets.remove(socket);
              if (!handedOff) {
                try {
                  socket.close();
                } catch (Exception ignored) {}
                if (path != null) path.close();
              }
            }
          });
        } catch (Exception error) {
          if (!closed) close();
        }
      }
    });
  }

  public boolean valid() {
    return !closed && invite.valid() && System.currentTimeMillis() < expires;
  }

  public String display() {
    return code.substring(0, 4) + "-" + code.substring(4);
  }

  public long expires() {
    return expires;
  }

  @Override
  public void close() {
    closed = true;
    if (aware != null) aware.close();
    aware = null;
    advertiser.close();
    topology.close();
    try {
      server.close();
    } catch (Exception ignored) {}
    for (Socket socket : sockets)
      try {
        socket.close();
      } catch (Exception ignored) {}
    sockets.clear();
    workers.shutdownNow();
  }

  public static Handoff find(Context context, String entered) throws Exception {
    String code = CodeExchange.normalize(entered);
    Context app = context.getApplicationContext();
    LocalNetworkTracker topology = new LocalNetworkTracker(app);
    topology.start();
    ScheduledExecutorService worker = Executors.newScheduledThreadPool(3);
    CompletableFuture<Handoff> result = new CompletableFuture<>();
    AwareCodePairing aware = AwareCodePairing.find(app, code, result);
    Map<String, LanEndpoint> endpoints = new ConcurrentHashMap<>();
    Map<String, Integer> attempts = new ConcurrentHashMap<>();
    Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    LanBrowser browser = new LanBrowser(
      app,
      topology,
      TYPE,
      new LanBrowser.Listener() {
        @Override
        public void onEndpoint(LanEndpoint endpoint) {
          byte[] id = endpoint.attributes.get("jam");
          if (id == null || result.isDone()) return;
          String jam = new String(id, StandardCharsets.UTF_8);
          try {
            if (!UUID.fromString(jam).toString().equals(jam)) return;
          } catch (Exception error) {
            return;
          }
          if (endpoints.putIfAbsent(endpoint.fingerprint, endpoint) == null)
            attempt(endpoint, jam);
        }

        @Override
        public void onServiceLost(String name, long networkHandle) {
          for (LanEndpoint endpoint : new ArrayList<>(endpoints.values()))
            if (
              endpoint.serviceName.equals(name) &&
              endpoint.discoveryHandle == networkHandle
            ) {
              endpoints.remove(endpoint.fingerprint, endpoint);
              attempts.remove(endpoint.fingerprint);
            }
        }

        private void attempt(LanEndpoint endpoint, String jam) {
          if (result.isDone() || endpoints.get(endpoint.fingerprint) != endpoint)
            return;
          int number = attempts.merge(endpoint.fingerprint, 1, Integer::sum);
          if (number > 3) return;
          try {
            worker.execute(() -> {
              if (
                result.isDone() ||
                endpoints.get(endpoint.fingerprint) != endpoint
              ) return;
              Socket socket = null;
              try {
                LanConnection.Result connected = LanConnection.connect(
                  endpoint,
                  topology.snapshot(),
                  4000
                );
                socket = connected.socket;
                sockets.add(socket);
                Handoff handoff = new Handoff(
                  CodeExchange.takeAndKeep(socket, jam, code),
                  socket,
                  "LAN",
                  connected.route.name(),
                  null
                );
                if (result.complete(handoff)) {
                  sockets.remove(socket);
                  socket = null;
                } else handoff.close();
              } catch (Exception ignored) {
                if (!result.isDone() && number < 3) try {
                  worker.schedule(
                    () -> attempt(endpoint, jam),
                    number == 1 ? 600 : 1500,
                    TimeUnit.MILLISECONDS
                  );
                } catch (RejectedExecutionException closed) {}
              } finally {
                if (socket != null) try {
                  socket.close();
                } catch (Exception ignored) {}
                if (socket != null) sockets.remove(socket);
              }
            });
          } catch (RejectedExecutionException closed) {}
        }

        @Override
        public void onLanStatus(String status) {
          android.util.Log.i("MorpheJam", "Code pairing " + status);
        }
      }
    );
    browser.start();
    Handoff found = null;
    try {
      found = result.get(30, TimeUnit.SECONDS);
      return found;
    } catch (TimeoutException error) {
      throw new IllegalArgumentException(
        "Code not found or expired. Keep both devices nearby, or scan the host QR invitation."
      );
    } finally {
      result.cancel(false);
      // An Aware data path belongs to the returned handoff. Closing its
      // discovery client here would tear down the freshly promoted socket.
      if (found == null || !"Aware".equals(found.transport)) aware.close();
      browser.close();
      topology.close();
      for (Socket socket : sockets)
        try {
          socket.close();
        } catch (Exception ignored) {}
      worker.shutdownNow();
    }
  }
}
