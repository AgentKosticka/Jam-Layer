package app.morphe.jam.companion;

import android.content.*;
import android.net.*;
import android.net.wifi.aware.*;
import android.os.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates independent LAN/Aware attempts. In Auto, authentication—not TCP arrival—chooses the winner. */
public final class Nearby implements AutoCloseable {

  public interface Listener {
    void connect(ConnectionCandidate candidate);
    void status(String message);

    default void state(String lanState, String awareState, boolean vpnActive) {}
  }

  public static final class ConnectionCandidate implements AutoCloseable {

    private enum State {
      PENDING,
      ACCEPTED,
      REJECTED,
      CLOSED,
    }

    private final Nearby owner;
    private final ChannelTransport connection;
    private final String transport, route;
    private final int generation;
    private final AwareDataPath path;
    private final LanEndpoint endpoint;
    private State state = State.PENDING;

    private ConnectionCandidate(
      Nearby owner,
      ChannelTransport connection,
      String transport,
      String route,
      int generation,
      AwareDataPath path,
      LanEndpoint endpoint
    ) {
      this.owner = owner;
      this.connection = connection;
      this.transport = transport;
      this.route = route;
      this.generation = generation;
      this.path = path;
      this.endpoint = endpoint;
    }

    public ChannelTransport connection() {
      return connection;
    }

    public String transport() {
      return transport;
    }

    public String route() {
      return route;
    }

    public int generation() {
      return generation;
    }

    public synchronized boolean accept() {
      if (state != State.PENDING) return false;
      if (!owner.accept(this)) {
        close();
        return false;
      }
      state = State.ACCEPTED;
      return true;
    }

    public synchronized void reject() {
      if (state == State.PENDING) {
        state = State.REJECTED;
        owner.discard(this);
        close();
        owner.rejected(this);
      }
    }

    boolean isPending() {
      synchronized (this) {
        return state == State.PENDING;
      }
    }

    @Override
    public synchronized void close() {
      if (state == State.CLOSED) return;
      state = State.CLOSED;
      try {
        connection.close();
      } catch (Exception ignored) {}
      if (path != null) path.close();
      owner.forget(this);
    }
  }

  private static final class PendingMessage {

    final PeerHandle peer;
    final byte[] body;
    final int id, generation;
    int retries;

    PendingMessage(PeerHandle peer, byte[] body, int id, int generation) {
      this.peer = peer;
      this.body = body;
      this.id = id;
      this.generation = generation;
    }
  }

  private final Context context;
  private final Invitation invite;
  private final boolean host;
  private final int port;
  private final String preference;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService workers = Executors.newCachedThreadPool();
  private final ConnectivityManager connectivity;
  private final LocalNetworkTracker topology;
  private final LocalNetworkTracker.Listener topologyListener;
  private final Map<String, LanEndpoint> endpoints = new HashMap<>();
  private final Map<String, Integer> lanRetries = new HashMap<>();
  private final Map<String, Integer> authenticationRetries = new HashMap<>();
  private final Set<String> lanConnecting = new HashSet<>();
  private final Set<ConnectionCandidate> candidates =
    ConcurrentHashMap.newKeySet();
  private final Map<PeerHandle, AwareDataPath> paths =
    new ConcurrentHashMap<>();
  private final Map<PeerHandle, Integer> pathRetries =
    new ConcurrentHashMap<>();
  private final Map<Integer, PendingMessage> messages = new HashMap<>();
  // Kept while a LAN socket is live so a later route loss can reuse the
  // already-discovered Aware peer instead of beginning discovery from zero.
  private final Set<PeerHandle> knownAwarePeers = new HashSet<>();
  private final Runnable retryAware = this::attachAware;
  private final Runnable checkAwareDiscovery = this::checkAwareDiscovery;
  private LanAdvertiser advertiser;
  private LanBrowser browser;
  private WifiAwareManager awareManager;
  private WifiAwareSession aware;
  private DiscoverySession session;
  private BroadcastReceiver awareReceiver;
  private boolean attaching;
  private boolean awarePeerSeen;
  private int awareGeneration,
    nextMessageId = 1;
  private volatile boolean closed;
  private volatile LocalNetworkTracker.Snapshot snapshot;
  private volatile ConnectionCandidate winner;
  private volatile String lanState = "idle", awareState = "idle";

  public Nearby(
    Context context,
    Invitation invite,
    boolean host,
    int port,
    String preference,
    Listener listener
  ) {
    this.context = context.getApplicationContext();
    this.invite = invite;
    this.host = host;
    this.port = port;
    this.preference = preference;
    this.listener = listener;
    connectivity = this.context.getSystemService(ConnectivityManager.class);
    topology = new LocalNetworkTracker(this.context);
    topologyListener = value -> {
      if (closed) return;
      snapshot = value;
      publishState();
      onTopologyChanged();
    };
  }

  public void start() {
    handler.post(() -> {
      if (closed) return;
      topology.start();
      topology.addListener(topologyListener);
      if (!"Aware".equals(preference)) startLan();
      if (!"LAN".equals(preference)) startAware();
    });
  }

  public String lanState() {
    return lanState;
  }

  public String awareState() {
    return awareState;
  }

  public boolean vpnActive() {
    LocalNetworkTracker.Snapshot value = snapshot;
    return value != null && value.vpnActive;
  }

  private void startLan() {
    if (
      Build.VERSION.SDK_INT >= 37 &&
      context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      lanState = "permission required";
      listener.status("Local-network permission is required for LAN discovery");
      publishState();
      return;
    }
    if (host) {
      advertiser = new LanAdvertiser(
        context,
        topology,
        "_morphejam._tcp.",
        "Jam-" + invite.jamId.substring(0, 8),
        port,
        attrs(),
        status -> {
          lanState = status;
          listener.status(status);
          publishState();
        }
      );
      advertiser.start();
    } else {
      browser = new LanBrowser(
        context,
        topology,
        "_morphejam._tcp.",
        new LanBrowser.Listener() {
          @Override
          public void onEndpoint(LanEndpoint endpoint) {
            byte[] id = endpoint.attributes.get("jam");
            if (
              id != null &&
              invite.jamId.equals(new String(id, StandardCharsets.UTF_8))
            ) connectLan(endpoint);
          }

          @Override
          public void onServiceLost(String name, long handle) {
            for (
              Iterator<Map.Entry<String, LanEndpoint>> it = endpoints
                .entrySet()
                .iterator();
              it.hasNext();

            ) {
              LanEndpoint endpoint = it.next().getValue();
              if (
                endpoint.serviceName.equals(name) &&
                endpoint.discoveryHandle == handle
              ) {
                lanRetries.remove(endpoint.fingerprint);
                authenticationRetries.remove(endpoint.fingerprint);
                lanConnecting.remove(endpoint.fingerprint);
                it.remove();
              }
            }
          }

          @Override
          public void onLanStatus(String status) {
            lanState = status;
            listener.status(status);
            publishState();
          }
        }
      );
      browser.start();
    }
  }

  private Map<String, byte[]> attrs() {
    Map<String, byte[]> value = new HashMap<>();
    value.put("jam", invite.jamId.getBytes(StandardCharsets.UTF_8));
    value.put("v", "1".getBytes(StandardCharsets.UTF_8));
    return value;
  }

  private void onTopologyChanged() {
    if (closed) return;
    for (LanEndpoint endpoint : new ArrayList<>(endpoints.values()))
      if (
        !lanConnecting.contains(endpoint.fingerprint) &&
        lanRetries.getOrDefault(endpoint.fingerprint, 0) < 4 &&
        authenticationRetries.getOrDefault(endpoint.fingerprint, 0) <= 3
      ) connectLan(endpoint);
  }

  private void connectLan(LanEndpoint endpoint) {
    if (
      closed || winner != null ||
      authenticationRetries.getOrDefault(endpoint.fingerprint, 0) > 3 ||
      lanConnecting.contains(endpoint.fingerprint)
    ) return;
    endpoints.put(endpoint.fingerprint, endpoint);
    lanConnecting.add(endpoint.fingerprint);
    lanState = "connecting";
    publishState();
    workers.execute(() -> {
      try {
        LanConnection.Result result = LanConnection.connect(
          endpoint,
          snapshot,
          5000
        );
        if (closed || winner != null) {
          try { result.socket.close(); } catch (Exception ignored) {}
          return;
        }
        handler.post(() -> {
          if (closed || winner != null) {
            try { result.socket.close(); } catch (Exception ignored) {}
            return;
          }
          lanRetries.remove(endpoint.fingerprint);
          lanState = "connected";
          publishState();
          try {
            deliver(
              new ConnectionCandidate(
                this,
                new SocketChannelTransport(result.socket),
                "LAN",
                result.route.name(),
                awareGeneration,
                null,
                endpoint
              )
            );
          } catch (Exception error) {
            try { result.socket.close(); } catch (Exception ignored) {}
            lanFailed(endpoint, error);
          }
        });
      } catch (Exception error) {
        handler.post(() -> lanFailed(endpoint, error));
      } finally {
        handler.post(() -> lanConnecting.remove(endpoint.fingerprint));
      }
    });
  }

  private void lanFailed(LanEndpoint endpoint, Exception error) {
    if (closed || winner != null) return;
    int attempt = lanRetries.getOrDefault(endpoint.fingerprint, 0) + 1;
    lanRetries.put(endpoint.fingerprint, attempt);
    boolean vpn = vpnActive();
    lanState =
      vpn && snapshot != null && snapshot.localNetworks.isEmpty()
        ? "VPN may be preventing local-network access"
        : "host discovered but unreachable";
    if (attempt <= 4) handler.postDelayed(
      () -> connectLan(endpoint),
      new long[] { 0, 500, 1500, 3000 }[attempt - 1]
    );
    listener.status(
      vpn && snapshot != null && snapshot.localNetworks.isEmpty()
        ? "VPN is preventing local-network access. Allow LAN access or exclude Jam Layer from the VPN."
        : "LAN host discovered but unreachable"
    );
    publishState();
    android.util.Log.w(
      "MorpheJam",
      "LAN connect failure network=" +
        endpoint.networkHandle +
        " " +
        error.getClass().getSimpleName()
    );
  }

  private void startAware() {
    if (Build.VERSION.SDK_INT < 29) {
      awareState = "requires Android 10";
      publishState();
      return;
    }
    awareManager = context.getSystemService(WifiAwareManager.class);
    if (awareManager == null) {
      awareState = "unavailable";
      publishState();
      return;
    }
    observeAwareState();
    attachAware();
  }

  private void observeAwareState() {
    if (awareReceiver != null) return;
    awareReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context ignored, Intent intent) {
        if (
          !WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED.equals(
            intent.getAction()
          )
        ) return;
        handler.post(() -> {
          if (closed) return;
          closeAware();
          attachAware();
        });
      }
    };
    try {
      IntentFilter filter = new IntentFilter(
        WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED
      );
      if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(
        awareReceiver,
        filter,
        Context.RECEIVER_NOT_EXPORTED
      );
      else context.registerReceiver(awareReceiver, filter);
    } catch (RuntimeException error) {
      awareReceiver = null;
    }
  }

  private boolean activeAware(int generation) {
    return !closed && generation == awareGeneration;
  }

  private boolean canConnectAware(int generation) {
    return activeAware(generation) && (host || winner == null);
  }

  private void attachAware() {
    WifiAwareManager manager = awareManager;
    if (
      closed || winner != null || attaching || aware != null || manager == null
    ) return;
    if (!manager.isAvailable()) {
      awareState = "unavailable; waiting";
      publishState();
      scheduleAwareRetry();
      return;
    }
    int generation = awareGeneration;
    attaching = true;
    awareState = "attaching";
    publishState();
    try {
      manager.attach(
        new AttachCallback() {
          @Override
          public void onAttachFailed() {
            if (!activeAware(generation)) return;
            attaching = false;
            awareState = "attach failed";
            publishState();
            scheduleAwareRetry();
          }

          @Override
          public void onAttached(WifiAwareSession value) {
            attaching = false;
            if (!activeAware(generation)) {
              try {
                value.close();
              } catch (Exception ignored) {}
              return;
            }
            aware = value;
            DiscoverySessionCallback callback = new DiscoverySessionCallback() {
              @Override
              public void onPublishStarted(PublishDiscoverySession value) {
                if (!activeAware(generation)) {
                  try {
                    value.close();
                  } catch (Exception ignored) {}
                  return;
                }
                session = value;
                awareState = "published";
                publishState();
              }

              @Override
              public void onSubscribeStarted(SubscribeDiscoverySession value) {
                if (!activeAware(generation)) {
                  try {
                    value.close();
                  } catch (Exception ignored) {}
                  return;
                }
                session = value;
                awareState = "discovering";
                awarePeerSeen = false;
                handler.removeCallbacks(checkAwareDiscovery);
                handler.postDelayed(checkAwareDiscovery, 12_000);
                publishState();
              }

              @Override
              public void onSessionConfigFailed() {
                restartAware(generation, "discovery failed");
              }

              @Override
              public void onSessionTerminated() {
                restartAware(generation, "discovery ended");
              }

              @Override
              public void onServiceDiscovered(
                PeerHandle peer,
                byte[] info,
                List<byte[]> filter
              ) {
                if (
                  activeAware(generation) &&
                  !host &&
                  info != null &&
                  invite.jamId.equals(new String(info, StandardCharsets.UTF_8))
                ) {
                  awarePeerSeen = true;
                  handler.removeCallbacks(checkAwareDiscovery);
                  knownAwarePeers.add(peer);
                  if (canConnectAware(generation))
                    sendAwareMessage(peer, invite.jamId, generation);
                }
              }

              @Override
              public void onMessageReceived(PeerHandle peer, byte[] body) {
                if (!activeAware(generation) || session == null) return;
                String value = new String(body, StandardCharsets.UTF_8);
                if (host && invite.jamId.equals(value)) {
                  requestPath(peer, generation);
                  sendAwareMessage(peer, "OK:" + invite.jamId, generation);
                } else if (
                  !host && ("OK:" + invite.jamId).equals(value)
                ) {
                  knownAwarePeers.add(peer);
                  if (canConnectAware(generation)) requestPath(peer, generation);
                }
              }

              @Override
              public void onMessageSendSucceeded(int id) {
                if (activeAware(generation)) messages.remove(id);
              }

              @Override
              public void onMessageSendFailed(int id) {
                if (activeAware(generation)) retryMessage(id);
              }
            };
            try {
              byte[] id = invite.jamId.getBytes(StandardCharsets.UTF_8);
              if (host) value.publish(
                new PublishConfig.Builder()
                  .setServiceName("morphejam")
                  .setServiceSpecificInfo(id)
                  .build(),
                callback,
                handler
              );
              else value.subscribe(
                new SubscribeConfig.Builder()
                  .setServiceName("morphejam")
                  .build(),
                callback,
                handler
              );
            } catch (RuntimeException error) {
              restartAware(generation, "discovery failed");
            }
          }
        },
        handler
      );
    } catch (RuntimeException error) {
      attaching = false;
      awareState = "unavailable";
      publishState();
      scheduleAwareRetry();
    }
  }

  private void restartAware(int generation, String status) {
    if (!activeAware(generation)) return;
    awareState = status;
    publishState();
    closeAware();
    scheduleAwareRetry();
  }

  /** Some vendor stacks retain a subscribe session that no longer receives beacons. */
  private void checkAwareDiscovery() {
    if (
      closed || host || winner != null || awarePeerSeen || session == null
    ) return;
    restartAware(awareGeneration, "Aware discovery retrying");
  }

  private void scheduleAwareRetry() {
    handler.removeCallbacks(retryAware);
    if (!closed && winner == null) handler.postDelayed(retryAware, 3000);
  }

  private void sendAwareMessage(PeerHandle peer, String value, int generation) {
    if (!activeAware(generation)) return;
    int id = nextMessageId++;
    if (nextMessageId < 1) nextMessageId = 1;
    PendingMessage message = new PendingMessage(
      peer,
      value.getBytes(StandardCharsets.UTF_8),
      id,
      generation
    );
    messages.put(id, message);
    sendAwareMessage(message);
  }

  private void sendAwareMessage(PendingMessage message) {
    if (!activeAware(message.generation) || session == null) {
      messages.remove(message.id);
      return;
    }
    try {
      session.sendMessage(message.peer, message.id, message.body);
    } catch (RuntimeException error) {
      retryMessage(message.id);
    }
  }

  private void retryMessage(int id) {
    PendingMessage message = messages.get(id);
    if (message == null || !activeAware(message.generation)) {
      messages.remove(id);
      return;
    }
    if (message.retries++ >= 2) {
      messages.remove(id);
      return;
    }
    handler.postDelayed(() -> sendAwareMessage(message), 300);
  }

  private void requestPath(PeerHandle peer, int generation) {
    if (
      !canConnectAware(generation) ||
      session == null ||
      paths.containsKey(peer) ||
      paths.size() >= 8
    ) return;
    int attempt = pathRetries.getOrDefault(peer, 0) + 1;
    try {
      String psk = SecureChannel.encode(
        SecureChannel.hmac(
          invite.secret,
          "aware-path-v1".getBytes(StandardCharsets.UTF_8)
        )
      );
      WifiAwareNetworkSpecifier.Builder builder =
        new WifiAwareNetworkSpecifier.Builder(session, peer).setPskPassphrase(
          psk
        );
      if (host) builder.setPort(port).setTransportProtocol(6);
      AwareDataPath path = new AwareDataPath(
        connectivity,
        handler,
        workers,
        builder.build(),
        0,
        !host,
        5000,
        generation,
        attempt,
        new AwareDataPath.Listener() {
          @Override
          public void onPathAvailable(AwareDataPath value) {
            if (activeAware(generation)) {
              awareState = host ? "path ready" : "path available";
              publishState();
            }
          }

          @Override
          public void onSocket(AwareDataPath value, Socket socket) {
            if (!canConnectAware(generation)) {
              try {
                socket.close();
              } catch (Exception ignored) {}
              value.close();
              return;
            }
            awareState = "socket connected";
            publishState();
            try {
              deliver(
                new ConnectionCandidate(
                  Nearby.this,
                  new SocketChannelTransport(socket),
                  "Aware",
                  "AWARE_NETWORK",
                  generation,
                  value,
                  null
                )
              );
            } catch (Exception error) {
              try { socket.close(); } catch (Exception ignored) {}
              value.close();
              awareFailed(peer, value, generation);
            }
          }

          @Override
          public void onFailure(
            AwareDataPath value,
            String phase,
            Exception error
          ) {
            handler.post(() -> awareFailed(peer, value, generation));
          }
        }
      );
      paths.put(peer, path);
      path.request();
    } catch (Exception error) {
      awareFailed(peer, null, generation);
    }
  }

  private void awareFailed(
    PeerHandle peer,
    AwareDataPath path,
    int generation
  ) {
    if (paths.get(peer) == path || path == null) paths.remove(peer);
    if (!canConnectAware(generation)) return;
    int attempt = pathRetries.getOrDefault(peer, 0) + 1;
    pathRetries.put(peer, attempt);
    awareState = "path failed, retry " + attempt + "/4";
    publishState();
    if (attempt <= 4) handler.postDelayed(
      () -> requestPath(peer, generation),
      new long[] { 500, 1000, 2000, 4000 }[attempt - 1] +
        (long) (Math.random() * 150)
    );
    else awareState = "path cooldown";
  }

  private void deliver(ConnectionCandidate candidate) {
    if (closed || (!host && winner != null)) {
      candidate.close();
      return;
    }
    candidates.add(candidate);
    listener.connect(candidate);
  }

  private synchronized boolean accept(ConnectionCandidate candidate) {
    if (closed || (!host && winner != null && winner != candidate)) return false;
    if (!host) winner = candidate;
    handler.post(() -> finishAccepted(candidate));
    return true;
  }

  private void finishAccepted(ConnectionCandidate candidate) {
    if (closed) return;
    if (host) {
      candidates.remove(candidate);
      return;
    }
    if (winner != candidate) return;
    for (ConnectionCandidate other : new ArrayList<>(candidates))
      if (other != candidate) other.close();
    lanState = "LAN".equals(candidate.transport()) ? "connected" : "cancelled";
    if ("LAN".equals(candidate.transport())) {
      // Keep the Aware discovery session alive as a warm fallback. A route
      // change can then reuse its peer handle instead of waiting to attach and
      // discover again.
      awareState = "standby";
      messages.clear();
      for (AwareDataPath path : paths.values()) path.close();
      paths.clear();
    } else if ("Aware".equals(candidate.transport())) {
      awareState = "connected";
      for (
        Iterator<Map.Entry<PeerHandle, AwareDataPath>> it = paths
          .entrySet()
          .iterator();
        it.hasNext();

      ) {
        AwareDataPath path = it.next().getValue();
        if (path != candidate.path) {
          path.close();
          it.remove();
        }
      }
    }
    publishState();
  }

  /** Reopens connection attempts without discarding warm LAN/Aware discovery. */
  public void resume() {
    handler.post(() -> {
      if (closed || host) return;
      ConnectionCandidate previous = winner;
      winner = null;
      if (previous != null) previous.close();
      lanState = "reconnecting";
      if (!"Aware".equals(preference) && browser == null) startLan();
      if (!"LAN".equals(preference)) {
        // A failed Aware socket invalidates its data path and peer state on
        // several vendor implementations. Reattach in that case. A LAN loss
        // has no Aware path to invalidate, so it can use the warm peer handle.
        if (
          aware == null ||
          (previous != null && "Aware".equals(previous.transport()))
        ) {
          closeAware();
          startAware();
        }
        else {
          awareState = "reconnecting";
          for (PeerHandle peer : new ArrayList<>(knownAwarePeers))
            sendAwareMessage(peer, invite.jamId, awareGeneration);
        }
      }
      publishState();
    });
  }

  private void discard(ConnectionCandidate candidate) {
    candidates.remove(candidate);
  }

  private void rejected(ConnectionCandidate candidate) {
    LanEndpoint endpoint = candidate.endpoint;
    handler.post(() -> {
      if (endpoint == null) {
        if (candidate.path != null)
          for (Map.Entry<PeerHandle, AwareDataPath> entry : paths.entrySet())
            if (entry.getValue() == candidate.path) {
              awareFailed(entry.getKey(), candidate.path, candidate.generation());
              break;
            }
        return;
      }
      if (closed || winner != null ||
          endpoints.get(endpoint.fingerprint) != endpoint) return;
      int count = authenticationRetries.getOrDefault(endpoint.fingerprint, 0) + 1;
      authenticationRetries.put(endpoint.fingerprint, count);
      if (count <= 3) handler.postDelayed(
        () -> connectLan(endpoint),
        count * 700L
      );
    });
  }

  private void forget(ConnectionCandidate candidate) {
    candidates.remove(candidate);
  }

  private void closeAware() {
    awareGeneration++;
    handler.removeCallbacks(retryAware);
    handler.removeCallbacks(checkAwareDiscovery);
    attaching = false;
    awarePeerSeen = false;
    messages.clear();
    knownAwarePeers.clear();
    pathRetries.clear();
    for (AwareDataPath path : paths.values()) path.close();
    paths.clear();
    if (session != null) try {
      session.close();
    } catch (Exception ignored) {}
    session = null;
    if (aware != null) try {
      aware.close();
    } catch (Exception ignored) {}
    aware = null;
  }

  private void publishState() {
    listener.state(lanState, awareState, vpnActive());
  }

  @Override
  public void close() {
    closed = true;
    handler.post(() -> {
      topology.removeListener(topologyListener);
      topology.close();
      if (advertiser != null) advertiser.close();
      advertiser = null;
      if (browser != null) browser.close();
      browser = null;
      if (awareReceiver != null) try {
        context.unregisterReceiver(awareReceiver);
      } catch (RuntimeException ignored) {}
      awareReceiver = null;
      closeAware();
      for (ConnectionCandidate candidate : new ArrayList<>(candidates))
        candidate.close();
      workers.shutdownNow();
    });
  }
}
