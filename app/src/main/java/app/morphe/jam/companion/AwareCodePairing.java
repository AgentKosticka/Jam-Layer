package app.morphe.jam.companion;

import android.content.*;
import android.net.*;
import android.net.wifi.aware.*;
import android.os.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

final class AwareCodePairing implements AutoCloseable {

  private static final String SERVICE = "morphepair",
    REQUEST = "PAIR:",
    READY = "READY:";

  private static final class Candidate {

    final PeerHandle peer;
    final String jam;

    Candidate(PeerHandle peer, String jam) {
      this.peer = peer;
      this.jam = jam;
    }
  }

  private static final class PendingMessage {

    final PeerHandle peer;
    final byte[] body;
    final int id;
    final int generation;
    int retries;

    PendingMessage(PeerHandle peer, byte[] body, int id, int generation) {
      this.peer = peer;
      this.body = body;
      this.id = id;
      this.generation = generation;
    }
  }

  private final Context context;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService workers = Executors.newCachedThreadPool();
  private final Map<PeerHandle, Candidate> candidates = new HashMap<>();
  private final Map<PeerHandle, AwareDataPath> paths =
    new ConcurrentHashMap<>();
  private final Map<PeerHandle, Integer> retries = new ConcurrentHashMap<>();
  private final ConnectivityManager connectivity;
  private final Map<Integer, PendingMessage> messages = new HashMap<>();
  private final WifiAwareManager manager;
  private final Runnable retryAware = this::attach;
  private final boolean host;
  private final String code, tag, jam;
  private final int port;
  private final CompletableFuture<CodePairing.Handoff> result;
  private WifiAwareSession aware;
  private DiscoverySession session;
  private BroadcastReceiver awareState;
  private boolean attaching;
  private volatile boolean closed;
  private int generation;
  private int nextMessageId = 1;

  private AwareCodePairing(
    Context context,
    Invitation invite,
    String code,
    int port,
    CompletableFuture<CodePairing.Handoff> result
  ) {
    this.context = context.getApplicationContext();
    host = invite != null;
    this.code = CodeExchange.normalize(code);
    tag = tag(this.code);
    jam = host ? invite.jamId : null;
    this.port = port;
    this.result = result;
    manager = this.context.getSystemService(WifiAwareManager.class);
    connectivity = this.context.getSystemService(ConnectivityManager.class);
  }

  static AwareCodePairing host(
    Context context,
    Invitation invite,
    String code,
    int port
  ) {
    AwareCodePairing pairing = new AwareCodePairing(
      context,
      invite,
      code,
      port,
      null
    );
    pairing.start();
    return pairing;
  }

  static AwareCodePairing find(
    Context context,
    String code,
    CompletableFuture<CodePairing.Handoff> result
  ) {
    AwareCodePairing pairing = new AwareCodePairing(
      context,
      null,
      code,
      0,
      result
    );
    pairing.start();
    return pairing;
  }

  private static String tag(String code) {
    try {
      return SecureChannel.encode(
        MessageDigest.getInstance("SHA-256").digest(
          ("morphejam-pair/1/" + code).getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void start() {
    handler.post(() -> {
      observeAwareState();
      attach();
    });
  }

  private boolean active(int expected) {
    return !closed && generation == expected;
  }

  private void attach() {
    if (
      closed ||
      attaching ||
      aware != null ||
      Build.VERSION.SDK_INT < 29 ||
      manager == null
    ) return;
    if (!manager.isAvailable()) {
      retryAttach();
      return;
    }
    int expected = generation;
    attaching = true;
    try {
      manager.attach(
        new AttachCallback() {
          @Override
          public void onAttachFailed() {
            if (!active(expected)) return;
            attaching = false;
            android.util.Log.w("MorpheJam", "Aware code attach failed");
            retryAttach();
          }

          @Override
          public void onAttached(WifiAwareSession next) {
            attaching = false;
            if (!active(expected)) {
              try {
                next.close();
              } catch (Exception ignored) {}
              return;
            }
            aware = next;
            DiscoverySessionCallback callback = new DiscoverySessionCallback() {
              @Override
              public void onPublishStarted(PublishDiscoverySession discovery) {
                if (!active(expected)) {
                  try {
                    discovery.close();
                  } catch (Exception ignored) {}
                  return;
                }
                session = discovery;
                android.util.Log.i("MorpheJam", "Aware code publisher ready");
              }

              @Override
              public void onSubscribeStarted(
                SubscribeDiscoverySession discovery
              ) {
                if (!active(expected)) {
                  try {
                    discovery.close();
                  } catch (Exception ignored) {}
                  return;
                }
                session = discovery;
                android.util.Log.i("MorpheJam", "Aware code discovery ready");
              }

              @Override
              public void onSessionConfigFailed() {
                android.util.Log.w("MorpheJam", "Aware code session config failed");
                restart(expected);
              }

              @Override
              public void onSessionTerminated() {
                android.util.Log.w("MorpheJam", "Aware code session terminated");
                restart(expected);
              }

              @Override
              public void onServiceDiscovered(
                PeerHandle peer,
                byte[] info,
                List<byte[]> filter
              ) {
                if (host || !active(expected) || session == null) return;
                String found = parseJam(info, tag);
                if (found == null) return;
                candidates.put(peer, new Candidate(peer, found));
                send(peer, REQUEST + tag);
                android.util.Log.i("MorpheJam", "Aware code host discovered");
              }

              @Override
              public void onMessageReceived(PeerHandle peer, byte[] message) {
                if (!active(expected) || session == null) return;
                String value = new String(message, StandardCharsets.UTF_8);
                android.util.Log.i(
                  "MorpheJam",
                  "Aware code message received role=" + (host ? "host" : "guest")
                );
                if (host) {
                  if (
                    (REQUEST + tag).equals(value) &&
                    path(peer, null, 0, expected)
                  ) send(peer, READY + jam + ":" + port);
                  return;
                }
                Candidate candidate = candidates.get(peer);
                int remotePort = readyPort(value, candidate);
                if (candidate != null && remotePort > 0) path(
                  peer,
                  candidate,
                  remotePort,
                  expected
                );
              }

              @Override
              public void onMessageSendSucceeded(int messageId) {
                if (active(expected)) {
                  messages.remove(messageId);
                  android.util.Log.i("MorpheJam", "Aware code message sent");
                }
              }

              @Override
              public void onMessageSendFailed(int messageId) {
                if (active(expected)) {
                  android.util.Log.w("MorpheJam", "Aware code message failed");
                  retryMessage(messageId);
                }
              }
            };
            try {
              if (host) next.publish(
                new PublishConfig.Builder()
                  .setServiceName(SERVICE)
                  .setServiceSpecificInfo(
                    (tag + ":" + jam).getBytes(StandardCharsets.UTF_8)
                  )
                  .build(),
                callback,
                handler
              );
              else next.subscribe(
                new SubscribeConfig.Builder().setServiceName(SERVICE).build(),
                callback,
                handler
              );
            } catch (Exception e) {
              restart(expected);
            }
          }
        },
        handler
      );
    } catch (Exception e) {
      attaching = false;
      retryAttach();
    }
  }

  private void observeAwareState() {
    if (awareState != null) return;
    awareState = new BroadcastReceiver() {
      @Override
      public void onReceive(Context ignored, Intent intent) {
        if (
          !WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED.equals(
            intent.getAction()
          )
        ) return;
        handler.post(() -> {
          if (closed) return;
          closeResources();
          if (manager != null && manager.isAvailable()) attach();
          else retryAttach();
        });
      }
    };
    try {
      IntentFilter filter = new IntentFilter(
        WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED
      );
      if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(
        awareState,
        filter,
        Context.RECEIVER_NOT_EXPORTED
      );
      else context.registerReceiver(awareState, filter);
    } catch (Exception ignored) {
      awareState = null;
    }
  }

  private void retryAttach() {
    if (!closed) {
      handler.removeCallbacks(retryAware);
      handler.postDelayed(retryAware, 1500);
    }
  }

  private void restart(int expected) {
    if (!active(expected)) return;
    closeResources();
    retryAttach();
  }

  private static String parseJam(byte[] info, String tag) {
    if (info == null) return null;
    String value = new String(info, StandardCharsets.UTF_8),
      prefix = tag + ":";
    if (!value.startsWith(prefix)) return null;
    String found = value.substring(prefix.length());
    try {
      return UUID.fromString(found).toString().equals(found) ? found : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static int readyPort(String value, Candidate candidate) {
    if (
      candidate == null || !value.startsWith(READY + candidate.jam + ":")
    ) return 0;
    try {
      int valuePort = Integer.parseInt(
        value.substring((READY + candidate.jam + ":").length())
      );
      return valuePort > 0 && valuePort <= 65535 ? valuePort : 0;
    } catch (Exception e) {
      return 0;
    }
  }

  /** Android allows a port-bearing Aware data path only when it is secured. */
  private String dataPathPassphrase() throws java.security.GeneralSecurityException {
    return SecureChannel.encode(
      SecureChannel.hmac(
        "morphejam-code-aware-path-v1".getBytes(StandardCharsets.UTF_8),
        code.getBytes(StandardCharsets.UTF_8)
      )
    );
  }

  private void send(PeerHandle peer, String value) {
    int expected = generation;
    if (!active(expected)) return;
    int id = nextMessageId++;
    if (nextMessageId < 1) nextMessageId = 1;
    PendingMessage message = new PendingMessage(
      peer,
      value.getBytes(StandardCharsets.UTF_8),
      id,
      expected
    );
    messages.put(id, message);
    send(message);
  }

  private void send(PendingMessage message) {
    if (!active(message.generation) || session == null) {
      messages.remove(message.id);
      return;
    }
    try {
      session.sendMessage(message.peer, message.id, message.body);
    } catch (Exception e) {
      retryMessage(message.id);
    }
  }

  private void retryMessage(int messageId) {
    PendingMessage message = messages.get(messageId);
    if (message == null || !active(message.generation)) {
      messages.remove(messageId);
      return;
    }
    if (message.retries++ >= 2) {
      messages.remove(messageId);
      return;
    }
    handler.postDelayed(() -> send(message), 300);
  }

  private boolean path(
    PeerHandle peer,
    Candidate candidate,
    int remotePort,
    int expected
  ) {
    if (
      !active(expected) ||
      session == null ||
      connectivity == null ||
      paths.size() >= 2
    ) return false;
    if (paths.containsKey(peer)) return true;
    try {
      WifiAwareNetworkSpecifier.Builder builder =
        new WifiAwareNetworkSpecifier.Builder(session, peer).setPskPassphrase(
          dataPathPassphrase()
        );
      if (host) builder.setPort(port).setTransportProtocol(6);
      int attempt = retries.getOrDefault(peer, 0) + 1;
      AwareDataPath path = new AwareDataPath(
        connectivity,
        handler,
        workers,
        builder.build(),
        remotePort,
        !host,
        5000,
        expected,
        attempt,
        new AwareDataPath.Listener() {
          @Override
          public void onPathAvailable(AwareDataPath value) {
            android.util.Log.i("MorpheJam", "Aware code path available");
          }

          @Override
          public void onSocket(AwareDataPath value, Socket socket) {
            if (!active(expected) || candidate == null) {
              try {
                socket.close();
              } catch (Exception ignored) {}
              value.close();
              return;
            }
            boolean handedOff = false;
            try {
              android.util.Log.i("MorpheJam", "Aware code path socket connected");
              CodePairing.Handoff handoff = new CodePairing.Handoff(
                CodeExchange.takeAndKeep(
                  socket,
                  candidate.jam,
                  code
                ),
                socket,
                "Aware",
                "AWARE_NETWORK",
                retainPath(value)
              );
              // Remove the path before completing the future: completion wakes
              // CodePairing.find(), whose cleanup closes all paths still owned
              // by this pairing instance.
              paths.remove(peer, value);
              retries.remove(peer);
              if (result != null && result.complete(handoff)) {
                handedOff = true;
              } else handoff.close();
            } catch (Exception error) {
              failedPath(peer, candidate, expected, value);
              return;
            } finally {
              if (!handedOff) {
                try {
                  socket.close();
                } catch (Exception ignored) {}
                value.close();
              }
            }
          }

          @Override
          public void onFailure(
            AwareDataPath value,
            String phase,
            Exception error
          ) {
            android.util.Log.w("MorpheJam", "Aware code path failed " + phase);
            handler.post(() -> failedPath(peer, candidate, expected, value));
          }
        }
      );
      paths.put(peer, path);
      android.util.Log.i("MorpheJam", "Aware code path requested");
      path.request();
      if (host) {
        handler.postDelayed(() -> {
          if (paths.get(peer) == path) {
            paths.remove(peer);
            path.close();
          }
        }, 30000);
      }
      return true;
    } catch (Exception e) {
      android.util.Log.w(
        "MorpheJam",
        "Aware code path setup failed: " + e.getClass().getSimpleName()
      );
      retry(peer, candidate, expected);
      return false;
    }
  }

  private void failedPath(
    PeerHandle peer,
    Candidate candidate,
    int expected,
    AwareDataPath path
  ) {
    if (paths.get(peer) != path) return;
    paths.remove(peer);
    path.close();
    retry(peer, candidate, expected);
  }

  /**
   * A host accepts an Aware socket through its ServerSocket. Once PAKE has
   * succeeded, retain that matching data-path request with the socket rather
   * than letting the pairing teardown terminate the live Jam connection.
   */
  AwareDataPath takeReadyPath(Socket socket) {
    if (!host || !(socket.getInetAddress() instanceof Inet6Address)) return null;
    for (Map.Entry<PeerHandle, AwareDataPath> entry : paths.entrySet()) {
      AwareDataPath path = entry.getValue();
      if (
        path.state() == AwareDataPath.State.AVAILABLE &&
        paths.remove(entry.getKey(), path)
      ) return path;
    }
    return null;
  }

  /** Keeps the discovery client alive while its transferred data path is live. */
  private AutoCloseable retainPath(AwareDataPath path) {
    return new AutoCloseable() {
      private boolean released;

      @Override
      public synchronized void close() {
        if (released) return;
        released = true;
        path.close();
        AwareCodePairing.this.close();
      }
    };
  }

  private void retry(PeerHandle peer, Candidate candidate, int expected) {
    if (!active(expected)) return;
    int attempt = retries.getOrDefault(peer, 0) + 1;
    if (attempt > 4) return;
    retries.put(peer, attempt);
    handler.postDelayed(() -> {
      if (!active(expected)) return;
      if (host) {
        if (path(peer, null, 0, expected)) send(peer, READY + jam + ":" + port);
      } else if (candidate != null) send(peer, REQUEST + tag);
    }, attempt * 500L);
  }

  private void closeResources() {
    generation++;
    attaching = false;
    handler.removeCallbacks(retryAware);
    candidates.clear();
    retries.clear();
    messages.clear();
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

  @Override
  public void close() {
    closed = true;
    handler.post(() -> {
      if (awareState != null) try {
        context.unregisterReceiver(awareState);
      } catch (Exception ignored) {}
      awareState = null;
      closeResources();
      workers.shutdownNow();
    });
  }
}
