package app.morphe.jam.companion;

import android.app.*;
import android.content.*;
import android.os.*;
import app.morphe.jam.ipc.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class JamService extends Service {

  public static volatile JamService active;
  private final ExecutorService workers = Executors.newFixedThreadPool(10);
  private final ScheduledExecutorService monitor =
    Executors.newSingleThreadScheduledExecutor();
  private PowerManager.WakeLock wakeLock;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Object lifecycle = new Object();
  private volatile FutureTask<CodePairing.Handoff> lookup;
  private volatile boolean destroyed;
  private String selectedMode = "Auto";
  private volatile JSONObject sharedQueue;
  private volatile long discoveryStarted;
  private volatile boolean allowGuestEdits = true;
  private final Object serial = new Object(),
    connectionLock = new Object();
  private final Map<String, String> completed = new ConcurrentHashMap<>(),
    payloads = new ConcurrentHashMap<>();
  private final Set<ChannelTransport> connections =
    ConcurrentHashMap.newKeySet();
  private volatile IJamBridge bridge;
  private volatile Invitation invitation;
  private volatile SecureChannel channel;

  private static final class WarmChannel {

    final SecureChannel channel;
    final Nearby.ConnectionCandidate candidate;

    WarmChannel(SecureChannel channel, Nearby.ConnectionCandidate candidate) {
      this.channel = channel;
      this.candidate = candidate;
    }
  }

  private volatile WarmChannel backupChannel;
  private final Object backupLock = new Object();
  private final java.util.concurrent.atomic.AtomicLong connectionEpoch =
    new java.util.concurrent.atomic.AtomicLong();
  private final Map<
    String,
    ChannelAuthority<SecureChannel>
  > channelAuthorities = new ConcurrentHashMap<>();
  private final Map<
    String,
    java.util.concurrent.atomic.AtomicInteger
  > participants = new ConcurrentHashMap<>();
  private volatile String bleState = "idle";
  private volatile String role = "Idle",
    transport = "None",
    message = "Open Jam in YouTube Music to pair this layer.",
    lanState = "idle",
    awareState = "idle",
    lanRoute = "";
  private volatile boolean vpnActive;
  private String identity = UUID.randomUUID().toString();
  private Nearby nearby;
  private ServerSocket server;
  private CodePairing codePairing;
  private volatile long sessionEpoch;
  private volatile boolean reconnectScheduled;
  private boolean bound;
  private final ServiceConnection binding = new ServiceConnection() {
    public void onServiceConnected(ComponentName n, IBinder b) {
      bridge = IJamBridge.Stub.asInterface(b);
      if (invitation == null) message = "YouTube Music connected";
    }

    public void onServiceDisconnected(ComponentName n) {
      bridge = null;
      message = "Waiting for YouTube Music";
    }

    public void onBindingDied(ComponentName n) {
      bridge = null;
      if (bound) {
        unbindService(this);
        bound = false;
      }
      main.postDelayed(() -> {
        if (!destroyed) bindMusic();
      }, 1000);
    }
  };

  private android.content.SharedPreferences prefs() {
    return getSharedPreferences("pair", 0);
  }

  public static Intent startIntent(Context c) {
    return new Intent(c, JamService.class).putExtra(
      "cap",
      c.getSharedPreferences("pair", 0).getString("cap", "")
    );
  }

  private final IJamCompanion.Stub binder = new IJamCompanion.Stub() {
    public String call(String capability, String request) {
      Trust.caller(JamService.this, prefs().getString("package", ""));
      Trust.capability(prefs().getString("cap", null), capability);
      if (
        request == null || request.length() > 32768
      ) throw new IllegalArgumentException("Request size");
      try {
        return BridgeProtocol.advertise(
          dispatch(BridgeProtocol.validate(new JSONObject(request)))
        ).toString();
      } catch (Exception e) {
        return error(e.getMessage()).toString();
      }
    }
  };

  public static JSONObject error(String message) {
    JSONObject r = new JSONObject();
    try {
      r.put("ok", false).put(
        "error",
        message == null ? "Operation failed" : message
      );
    } catch (JSONException ignored) {}
    return r;
  }

  private static JSONObject ok() throws JSONException {
    return new JSONObject().put("ok", true);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    active = this;
    bindMusic();
    wakeLock = getSystemService(PowerManager.class).newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "MorpheJam:session"
    );
    monitor.scheduleWithFixedDelay(
      () -> {
        Invitation i = invitation;
        if (i == null) return;
        if (!i.valid()) {
          synchronized (lifecycle) {
            if (invitation == i) {
              end();
              message = "Session expired";
            }
          }
          return;
        }
        try {
          if ("Host".equals(role)) {
            synchronized (serial) {
              if (invitation != i) return;
              snapshotHost(i);
            }
          } else if (channel != null) {
            sync(i);
          } else if (System.currentTimeMillis() - discoveryStarted > 15000) {
            discoveryStarted = System.currentTimeMillis();
            reconnect(i);
          }
        } catch (Exception ignored) {}
      },
      500,
      500,
      TimeUnit.MILLISECONDS
    );
    monitor.scheduleWithFixedDelay(
      () -> workers.execute(this::pingBackup),
      30,
      30,
      TimeUnit.SECONDS
    );
  }

  private boolean channelRole(SecureChannel peer, String role)
    throws Exception {
    peer.setReadTimeout(3000);
    peer.send(
      new JSONObject()
        .put("op", "CHANNEL")
        .put("version", 1)
        .put("role", role)
        .put("epoch", connectionEpoch.incrementAndGet())
        .toString()
    );
    return new JSONObject(peer.receive()).optBoolean("ok");
  }

  private void pingBackup() {
    synchronized (backupLock) {
      WarmChannel standby = backupChannel;
      if (standby == null) return;
      try {
        standby.channel.setReadTimeout(2000);
        standby.channel.send(new JSONObject().put("op", "PING").toString());
        if (
          !new JSONObject(standby.channel.receive()).optBoolean("ok")
        ) throw new IOException("Backup ping failed");
      } catch (Exception error) {
        if (backupChannel == standby) backupChannel = null;
        standby.candidate.close();
      }
    }
  }

  /** Called with client request serialization held, outside the lifecycle monitor. */
  private boolean promoteBackup(Invitation expected) {
    synchronized (backupLock) {
      WarmChannel standby = backupChannel;
      if (standby == null) return false;
      try {
        if (!channelRole(standby.channel, "PRIMARY")) throw new IOException(
          "Stale channel epoch"
        );
        synchronized (lifecycle) {
          if (
            invitation != expected ||
            !expected.valid() ||
            backupChannel != standby
          ) return false;
          backupChannel = null;
          channel = standby.channel;
          transport = displayTransport(standby.candidate);
          lanRoute = standby.candidate.route();
          message = "Authenticated host connected";
          nearby.promoteBackup(standby.candidate);
          nearby.diagnostics.event("BACKUP_PROMOTED_" + transport, null, 0, "");
          workers.execute(() -> sync(expected));
          return true;
        }
      } catch (Exception error) {
        if (backupChannel == standby) backupChannel = null;
        standby.candidate.close();
        return false;
      }
    }
  }

  public void bindMusic() {
    if (bound) return;
    String pkg = prefs().getString("package", "");
    if (pkg.isEmpty()) return;
    try {
      bound = bindService(
        new Intent().setComponent(new ComponentName(pkg, Trust.BRIDGE_SERVICE)),
        binding,
        BIND_AUTO_CREATE
      );
    } catch (Exception e) {
      message = "Pair YouTube Music again";
    }
  }

  public void rebindMusic() {
    if (bound) {
      try {
        unbindService(binding);
      } catch (Exception ignored) {}
      bound = false;
    }
    bridge = null;
    bindMusic();
  }

  @Override
  public IBinder onBind(Intent i) {
    return binder;
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
      new NotificationChannel(
        "jam",
        "Jam session",
        NotificationManager.IMPORTANCE_LOW
      )
    );
    Intent player = getPackageManager().getLaunchIntentForPackage(
      getSharedPreferences("pair", 0).getString(
        "package",
        "app.morphe.jam.probe.music"
      )
    );
    Notification.Builder notification = new Notification.Builder(this, "jam")
      .setSmallIcon(R.drawable.ic_jam_notification)
      .setContentTitle("Jam Layer")
      .setContentText("Manage your Jam in YouTube Music");
    if (player != null) notification.setContentIntent(
      PendingIntent.getActivity(
        this,
        0,
        player,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
      )
    );
    startForeground(41, notification.build());
    try {
      Trust.capability(
        prefs().getString("cap", null),
        i == null ? null : i.getStringExtra("cap")
      );
    } catch (SecurityException denied) {
      if (invitation == null) stopSelf(id);
      return START_NOT_STICKY;
    }
    bindMusic();
    // A start may race with an immediate END or be used only for pairing.
    // Keep the bound bridge warm, but never retain an idle foreground service.
    main.postDelayed(() -> {
      if ("Idle".equals(role)) finishForeground();
    }, 1500);
    return START_NOT_STICKY;
  }

  private void finishForeground() {
    main.post(() -> {
      synchronized (lifecycle) {
        if (!"Idle".equals(role)) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
      }
    });
  }

  public JSONObject state() {
    try {
      return ok()
        .put("role", role)
        .put("transport", transport)
        .put(
          "backupTransport",
          backupChannel == null ? "None" : backupChannel.candidate.transport()
        )
        .put("message", message)
        .put("paired", bridge != null)
        .put(
          "peers",
          "Host".equals(role) ? participants.size() : channel == null ? 0 : 1
        )
        .put("allowGuestEdits", allowGuestEdits)
        .put("lanState", lanState)
        .put("awareState", awareState)
        .put("bleState", bleState)
        .put("vpnActive", vpnActive)
        .put("lanRoute", lanRoute)
        .put("transportRoute", lanRoute);
    } catch (Exception e) {
      return error("State unavailable");
    }
  }

  public String invite() {
    Invitation i = invitation;
    return i != null && "Host".equals(role) ? i.uri() : "";
  }

  public JSONObject joinCode(String code, String mode) throws Exception {
    CodeExchange.normalize(code);
    final long epoch;
    final FutureTask<CodePairing.Handoff> search =
      new FutureTask<CodePairing.Handoff>(() ->
        CodePairing.find(this, code, mode)
      ) {
        @Override
        protected void set(CodePairing.Handoff value) {
          synchronized (lifecycle) {
            if (isCancelled()) value.close();
            else super.set(value);
          }
        }
      };
    synchronized (lifecycle) {
      resetSession();
      epoch = sessionEpoch;
      role = "Joining";
      message = "Finding the code on nearby devices…";
      lookup = search;
      workers.execute(search);
    }
    try {
      CodePairing.Handoff handoff = search.get();
      synchronized (lifecycle) {
        if (epoch != sessionEpoch) {
          handoff.close();
          return error("Joining cancelled");
        }
        lookup = null;
        join(handoff.invitation, mode, handoff);
      }
      return view();
    } catch (Exception e) {
      synchronized (lifecycle) {
        if (epoch == sessionEpoch) {
          end();
          message =
            e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
        }
      }
      return error(
        e instanceof CancellationException ? "Joining cancelled" : message
      );
    } finally {
      synchronized (lifecycle) {
        if (lookup == search) lookup = null;
      }
    }
  }

  public void host(String mode) {
    synchronized (lifecycle) {
      resetSession();
      Invitation next = new Invitation();
      invitation = next;
      role = "Host";
      selectedMode = mode;
      transport = "Starting";
      message = "Starting host";
      wakeLock.acquire(12 * 60 * 60 * 1000L);
      workers.execute(() -> {
        try {
          ServerSocket listening = new ServerSocket(0);
          if (invitation != next || !next.valid()) {
            listening.close();
            return;
          }
          synchronized (lifecycle) {
            if (invitation != next) {
              listening.close();
              return;
            }
            server = listening;
            nearby = new Nearby(
              this,
              next,
              true,
              listening.getLocalPort(),
              mode,
              listenerFor(next)
            );
            nearby.start();
            message = "Host ready; share the QR invitation";
          }
          // Discovery and accepts must not wait for a slow native queue snapshot.
          while (invitation == next && next.valid()) {
            Socket socket = listening.accept();
            if (connections.size() >= 8) {
              socket.close();
              continue;
            }
            try {
              ChannelTransport connection = new SocketChannelTransport(socket);
              synchronized (lifecycle) {
                if (invitation != next) {
                  connection.close();
                  continue;
                }
                connections.add(connection);
              }
              workers.execute(() -> serve(connection, next, null));
            } catch (Exception error) {
              try {
                socket.close();
              } catch (Exception ignored) {}
            }
          }
        } catch (Exception e) {
          synchronized (lifecycle) {
            if (invitation == next) {
              android.util.Log.e("MorpheJam", "Host failed", e);
              end();
              message = "Host stopped: " + e.getClass().getSimpleName();
            }
          }
        }
      });
    }
  }

  public void join(String value, String mode) {
    join(value, mode, null);
  }

  private void join(String value, String mode, CodePairing.Handoff handoff) {
    synchronized (lifecycle) {
      final Invitation next = new Invitation(value);
      resetSession();
      invitation = next;
      role = "Participant";
      selectedMode = mode;
      transport = "Connecting";
      wakeLock.acquire(Math.max(1, next.expires - System.currentTimeMillis()));
      boolean reusePairing =
        handoff != null &&
        ("Auto".equals(mode) || mode.equals(handoff.transport));
      if (!reusePairing && handoff != null) handoff.close();
      message = reusePairing
        ? "Authenticating paired host"
        : "Discovering host";
      discoveryStarted = System.currentTimeMillis();
      nearby = new Nearby(this, next, false, 0, mode, listenerFor(next));
      nearby.start();
      if (reusePairing) {
        ChannelTransport connection = null;
        try {
          connection = handoff.takeConnection();
          // Register before handing the connection to a worker so end() can close
          // it if the user cancels during the Jam handshake.
          connections.add(connection);
          nearby.pairedConnection(connection, handoff.transport, handoff.route, handoff.endpoint);
        } catch (Exception error) {
          if (connection != null) {
            connections.remove(connection);
            try {
              connection.close();
            } catch (Exception ignored) {}
          }
          handoff.close();
          android.util.Log.w(
            "MorpheJam",
            "Paired connection unavailable",
            error
          );
        }
      }
    }
  }

  private boolean acceptPairedParticipant(
    Invitation expected,
    CodePairing.Handoff handoff
  ) {
    synchronized (lifecycle) {
      if (
        invitation != expected ||
        !"Host".equals(role) ||
        connections.size() >= 8
      ) {
        handoff.close();
        return false;
      }
      try {
        ChannelTransport connection = handoff.takeConnection();
        connections.add(connection);
        transport = handoff.transport;
        lanRoute = handoff.route;
        workers.execute(() -> serve(connection, expected, null));
        android.util.Log.i(
          "MorpheJam",
          "Promoted paired participant transport=" + handoff.transport
        );
        return true;
      } catch (Exception error) {
        handoff.close();
        return false;
      }
    }
  }

  private void reconnect(Invitation expected) {
    final long epoch = sessionEpoch;
    if (reconnectScheduled) return;
    reconnectScheduled = true;
    monitor.schedule(
      () -> {
        synchronized (lifecycle) {
          if (sessionEpoch != epoch) return;
          reconnectScheduled = false;
          if (
            invitation != expected || sessionEpoch != epoch || channel != null
          ) return;
          if (nearby != null) nearby.resume();
          else {
            nearby = new Nearby(
              this,
              expected,
              false,
              0,
              selectedMode,
              listenerFor(expected)
            );
            nearby.start();
          }
        }
      },
      500,
      TimeUnit.MILLISECONDS
    );
  }

  private Nearby.Listener listenerFor(Invitation session) {
    return new Nearby.Listener() {
      public void bleState(String state) {
        synchronized (lifecycle) {
          if (invitation == session) listener.bleState(state);
        }
      }

      public void status(String state) {
        synchronized (lifecycle) {
          if (invitation == session) listener.status(state);
        }
      }

      public void state(String lan, String aware, boolean vpn) {
        synchronized (lifecycle) {
          if (invitation == session) listener.state(lan, aware, vpn);
        }
      }

      public void connect(Nearby.ConnectionCandidate candidate) {
        synchronized (lifecycle) {
          if (invitation == session) listener.connect(candidate);
          else candidate.reject();
        }
      }
    };
  }

  private final Nearby.Listener listener = new Nearby.Listener() {
    public void bleState(String state) {
      bleState = state;
    }

    public void status(String m) {
      if (channel == null) message = m;
      android.util.Log.i("MorpheJam", m);
    }

    public void state(String lan, String aware, boolean vpn) {
      lanState = lan;
      awareState = aware;
      vpnActive = vpn;
    }

    public void connect(Nearby.ConnectionCandidate offered) {
      final Invitation expected = invitation;
      final long epoch = sessionEpoch;
      if (expected == null || !expected.valid()) {
        offered.reject();
        return;
      }
      if ("Host".equals(role)) {
        if (connections.size() >= 8) {
          offered.reject();
          return;
        }
        workers.execute(() -> serve(offered.connection(), expected, offered));
        return;
      }
      if (!"Participant".equals(role)) {
        offered.reject();
        return;
      }
      workers.execute(() -> {
        SecureChannel authenticated = null;
        boolean retained = false;
        try {
          authenticated = new SecureChannel(
            offered.connection(),
            false,
            expected.jamId,
            expected.secret,
            identity
          );
          // This extension runs inside the existing encrypted channel. Old hosts
          // reject the unknown op and remain usable as primary-only sessions.
          boolean roles = channelRole(authenticated, "BACKUP");
          synchronized (connectionLock) {
            if (
              invitation != expected ||
              sessionEpoch != epoch ||
              !expected.valid()
            ) return;
            boolean primary =
              channel == null ||
              ("BLE".equals(transport) && !"BLE".equals(offered.transport()));
            if (
              primary && roles && !channelRole(authenticated, "PRIMARY")
            ) throw new IOException("Channel promotion rejected");
            synchronized (lifecycle) {
              if (
                invitation != expected ||
                sessionEpoch != epoch ||
                !expected.valid() ||
                !"Participant".equals(role)
              ) return;
              if (primary && offered.accept()) {
                SecureChannel previous = channel;
                channel = authenticated;
                if (previous != null) try {
                  previous.close();
                } catch (Exception ignored) {}
                connections.removeIf(ChannelTransport::isClosed);
                connections.add(offered.connection());
                transport = displayTransport(offered);
                lanRoute = offered.route();
                message = "Authenticated host connected";
                retained = true;
                android.util.Log.i(
                  "MorpheJam",
                  "Authenticated transport=" +
                    offered.transport() +
                    " route=" +
                    offered.route()
                );
                workers.execute(() -> sync(expected));
              } else if (
                !primary &&
                roles &&
                backupChannel == null &&
                offered.acceptBackup()
              ) {
                backupChannel = new WarmChannel(authenticated, offered);
                connections.add(offered.connection());
                retained = true;
              }
            }
          }
        } catch (Exception error) {
          if (
            invitation == expected && sessionEpoch == epoch && channel == null
          ) message =
            "Host authentication failed; waiting for another transport";
        } finally {
          if (!retained) {
            if (authenticated != null) try {
              authenticated.close();
            } catch (Exception ignored) {}
            offered.reject();
          }
        }
      });
    }
  };

  /** Existing music patches recognize LAN and Aware; the route retains the precise medium. */
  private static String displayTransport(Nearby.ConnectionCandidate candidate) {
    return "Nearby".equals(candidate.transport())
      ? "Aware"
      : candidate.transport();
  }

  private void serve(
    ChannelTransport connection,
    Invitation session,
    Nearby.ConnectionCandidate offered
  ) {
    String participant = null;
    try (
      SecureChannel peer = new SecureChannel(
        connection,
        true,
        session.jamId,
        session.secret,
        null
      )
    ) {
      synchronized (lifecycle) {
        if (invitation != session) return;
        if (offered != null && !offered.accept()) return;
        if (offered != null) {
          connections.add(connection);
          transport = displayTransport(offered);
          lanRoute = offered.route();
        }
        android.util.Log.i("MorpheJam", "Authenticated participant");
        participant = peer.clientId;
        participants
          .computeIfAbsent(participant, id ->
            new java.util.concurrent.atomic.AtomicInteger()
          )
          .incrementAndGet();
        message = "Participant authenticated";
      }
      while (invitation == session && session.valid()) {
        String request = peer.receive();
        JSONObject command = new JSONObject(request);
        String op = command.optString("op");
        if ("CHANNEL".equals(op)) {
          boolean allowed = false;
          synchronized (serial) {
            if (invitation == session && command.optInt("version") == 1) {
              ChannelAuthority<SecureChannel> authority =
                channelAuthorities.computeIfAbsent(peer.clientId, id ->
                  new ChannelAuthority<>()
                );
              if ("BACKUP".equals(command.optString("role"))) allowed = true;
              else if ("PRIMARY".equals(command.optString("role"))) allowed =
                authority.promote(peer, command.optLong("epoch"));
            }
          }
          peer.send(
            new JSONObject().put("ok", allowed).put("version", 1).toString()
          );
          continue;
        }
        if ("PING".equals(op)) {
          peer.send(ok().toString());
          continue;
        }
        if ("SYNC".equals(op)) {
          // Published snapshots are immutable after assignment. A slow native
          // edit must not block heartbeats for every connected participant.
          JSONObject value = sharedQueue;
          if (value == null) synchronized (serial) {
            value = sharedQueue;
            if (value == null) value = snapshotHost(session);
          }
          value = new JSONObject(value.toString());
          JSONObject clock = value.optJSONObject("clock");
          if (clock != null) clock.put(
            "age",
            Math.max(
              0,
              SystemClock.elapsedRealtime() -
                clock.optLong("sampledAt", SystemClock.elapsedRealtime())
            )
          );
          String reply = (
            value.optBoolean("ok") &&
            value.optString("revision").equals(command.optString("revision"))
              ? ok()
                  .put("unchanged", true)
                  .put("clock", value.optJSONObject("clock"))
              : value
          ).toString();
          peer.send(reply);
          continue;
        }
        if (
          !Arrays.asList(
            "SNAPSHOT",
            "ADD",
            "PLAY_NEXT",
            "REMOVE",
            "MOVE",
            "PLAY",
            "SEEK",
            "SKIP_NEXT",
            "SKIP_PREVIOUS"
          ).contains(op)
        ) {
          peer.send(error("Unsupported operation").toString());
          continue;
        }
        peer.send(hostCall(command, peer.clientId, session, peer).toString());
      }
    } catch (Exception e) {
      android.util.Log.i(
        "MorpheJam",
        "Participant disconnected: " + e.getClass().getSimpleName()
      );
    } finally {
      if (participant != null) {
        final String id = participant;
        synchronized (lifecycle) {
          if (invitation == session) participants.computeIfPresent(
            id,
            (key, count) -> count.decrementAndGet() == 0 ? null : count
          );
        }
      }
      connections.remove(connection);
      try {
        connection.close();
      } catch (Exception ignored) {}
      if (offered != null) offered.close();
    }
  }

  private JSONObject hostCall(
    JSONObject request,
    String client,
    Invitation expected
  ) throws Exception {
    return hostCall(request, client, expected, null);
  }

  private JSONObject hostCall(
    JSONObject request,
    String client,
    Invitation expected,
    SecureChannel peer
  ) throws Exception {
    synchronized (serial) {
      ChannelAuthority<SecureChannel> authority = channelAuthorities.get(
        client
      );
      if (
        peer != null && authority != null && !authority.permits(peer)
      ) return error("Channel is not primary");
      if (expected == null || invitation != expected) return error(
        "Jam session ended"
      );
      String op = request.optString("op");
      if ("SNAPSHOT".equals(op)) return snapshotHost(expected);
      if (!identity.equals(client) && !allowGuestEdits) return error(
        "The host has locked guest edits"
      );
      String id = request.getString("id");
      if (!UUID.fromString(id).toString().equals(id)) return error(
        "Invalid command id"
      );
      String key = expected.jamId + ":" + client + ":" + id,
        body = request.toString();
      if (payloads.containsKey(key)) {
        if (!payloads.get(key).equals(body)) return error("Command id reused");
        String result = completed.get(key);
        return result == null
          ? error("Jam session ended")
          : new JSONObject(result);
      }
      if (payloads.size() >= 4096) return error(
        "Session command limit reached; start a new session"
      );
      payloads.put(key, body);
      JSONObject response;
      try {
        response = music(request);
      } catch (Exception e) {
        response = error("Outcome unknown; refresh queue before another edit");
      }
      synchronized (lifecycle) {
        if (invitation != expected) return error("Jam session ended");
        if (response.optBoolean("ok") && response.has("items")) sharedQueue =
          response;
        completed.put(key, response.toString());
      }
      return response;
    }
  }

  private JSONObject music(JSONObject request) throws Exception {
    IJamBridge b = bridge;
    if (b == null) return error(
      "Host YouTube Music is not connected; start a song"
    );
    return BridgeProtocol.validate(
      new JSONObject(
        b.call(
          prefs().getString("cap", ""),
          BridgeProtocol.advertise(
            new JSONObject(request.toString())
          ).toString()
        )
      )
    );
  }

  private JSONObject snapshotHost(Invitation expected) {
    try {
      JSONObject value = music(new JSONObject().put("op", "SNAPSHOT"));
      if (value.optBoolean("ok") && value.has("items")) {
        synchronized (lifecycle) {
          if (invitation != expected || expected == null) return error(
            "Jam session ended"
          );
          sharedQueue = value;
        }
        return value;
      }
      String failure = value.optString("error", "Host queue is unavailable");
      if (invitation == expected && !failure.equals(message)) {
        message = failure;
        android.util.Log.w("MorpheJam", "Host snapshot: " + failure);
      }
      return value;
    } catch (Exception e) {
      String failure =
        "Host queue unavailable: " + e.getClass().getSimpleName();
      if (invitation == expected && !failure.equals(message)) {
        message = failure;
        android.util.Log.w("MorpheJam", failure, e);
      }
      return error(failure);
    }
  }

  private void sync(Invitation expected) {
    if (invitation != expected || channel == null) return;
    try {
      JSONObject value = dispatch(
        new JSONObject()
          .put("op", "SYNC")
          .put(
            "revision",
            sharedQueue == null ? "" : sharedQueue.optString("revision")
          )
      );
      if (
        !value.optBoolean("ok") &&
        !value.optBoolean("unchanged") &&
        !value.optBoolean("recovered")
      ) {
        String failure = value.optString("error");
        synchronized (lifecycle) {
          if (invitation == expected && !failure.isEmpty()) message = failure;
        }
      }
    } catch (Exception e) {
      android.util.Log.w("MorpheJam", "Host sync failed", e);
    }
  }

  public JSONObject dispatch(JSONObject request) throws Exception {
    if ("HELLO".equals(request.optString("op"))) return ok();
    if ("STATE".equals(request.optString("op"))) return state();
    if ("HOST".equals(request.optString("op"))) {
      host(request.optString("transport", "Auto"));
      return view();
    }
    if ("JOIN".equals(request.optString("op"))) {
      String value = request.getString("invite").trim();
      if (!value.startsWith("morphejam://")) return joinCode(
        value,
        request.optString("transport", "Auto")
      );
      join(value, request.optString("transport", "Auto"));
      return view();
    }
    if ("INVITE".equals(request.optString("op"))) {
      Invitation currentInvite = invitation;
      String value = invite();
      if (value.isEmpty()) return error("Only the host can invite");
      com.google.zxing.common.BitMatrix bits =
        new com.google.zxing.MultiFormatWriter().encode(
          value,
          com.google.zxing.BarcodeFormat.QR_CODE,
          600,
          600
        );
      android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
        600,
        600,
        android.graphics.Bitmap.Config.ARGB_8888
      );
      int[] pixels = new int[600 * 600];
      for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[
        y * 600 + x
      ] = bits.get(x, y) ? 0xff000000 : 0xffffffff;
      bitmap.setPixels(pixels, 0, 600, 0, 0, 600, 600);
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes);
      bitmap.recycle();
      JSONObject reply = ok()
        .put("invite", value)
        .put(
          "qr",
          android.util.Base64.encodeToString(
            bytes.toByteArray(),
            android.util.Base64.NO_WRAP
          )
        );
      synchronized (lifecycle) {
        try {
          // Endpoint hints can refresh while the QR bitmap is being encoded.
          if (
            currentInvite == null || invitation != currentInvite
          ) return error("Jam session ended");
          if (codePairing == null || !codePairing.valid()) {
            if (codePairing != null) codePairing.close();
            Invitation expected = invitation;
            codePairing = new CodePairing(this, expected, handoff ->
              acceptPairedParticipant(expected, handoff)
            );
          }
          reply
            .put("code", codePairing.display())
            .put("codeExpires", codePairing.expires());
        } catch (Exception e) {
          reply.put(
            "codeError",
            "Short code sharing is unavailable. QR invitations remain available."
          );
        }
      }
      return reply;
    }
    if ("VIEW".equals(request.optString("op"))) {
      return view();
    }
    if ("GUEST_EDITS".equals(request.optString("op"))) {
      if (!"Host".equals(role)) return error(
        "Only the host can change permissions"
      );
      allowGuestEdits = request.getBoolean("allow");
      return ok();
    }
    if ("END".equals(request.optString("op"))) {
      end();
      return view();
    }
    Invitation current = invitation;
    if (current == null || !current.valid()) return error(
      "No active Jam session"
    );
    if ("Host".equals(role)) return hostCall(request, identity, current);
    synchronized (connectionLock) {
      final SecureChannel activeChannel = channel;
      if (invitation != current) return error("Jam session ended");
      if (activeChannel == null) return error("Host is not connected");
      try {
        // Queue synchronization is a heartbeat. Detect a dead route promptly;
        // leave longer native queue edits their existing completion budget.
        activeChannel.setReadTimeout(
          "SYNC".equals(request.optString("op")) ? 8000 : 45000
        );
        long sent = SystemClock.elapsedRealtime();
        activeChannel.send(request.toString());
        JSONObject value = new JSONObject(activeChannel.receive());
        JSONObject clock = value.optJSONObject("clock");
        if (clock != null) clock
          .put("receivedAt", SystemClock.elapsedRealtime())
          .put(
            "age",
            Math.min(
              4000,
              clock.optLong("age") + (SystemClock.elapsedRealtime() - sent) / 2
            )
          );
        synchronized (lifecycle) {
          if (invitation != current) return error("Jam session ended");
          if (value.optBoolean("ok") && value.has("items")) sharedQueue = value;
          else if (
            value.optBoolean("unchanged") &&
            value.has("clock") &&
            sharedQueue != null
          ) {
            JSONObject copy = new JSONObject(sharedQueue.toString());
            copy.put("clock", value.getJSONObject("clock"));
            sharedQueue = copy;
          }
        }
        return value;
      } catch (Exception e) {
        try {
          activeChannel.close();
        } catch (Exception ignored) {}
        if (promoteBackup(current)) return error(
          "Outcome unknown; refresh before editing. Backup promoted."
        ).put("recovered", true);
        synchronized (lifecycle) {
          if (invitation != current) return error("Jam session ended");
          android.util.Log.w("MorpheJam", "Host channel failed", e);
          try {
            activeChannel.close();
          } catch (Exception ignored) {}
          channel = null;
          connections.removeIf(ChannelTransport::isClosed);
          transport = "Disconnected";
          message = "Reconnecting to host";
          reconnect(current);
          return error(
            "Outcome unknown; refresh before editing. Reconnecting."
          );
        }
      }
    }
  }

  public void end() {
    synchronized (lifecycle) {
      resetSession();
      message = "Start or join a Jam from YouTube Music";
      finishForeground();
    }
  }

  private JSONObject view() throws JSONException {
    synchronized (lifecycle) {
      JSONObject queue = sharedQueue;
      JSONObject value =
        queue == null ? ok() : new JSONObject(queue.toString());
      if (queue == null && "Participant".equals(role)) value.put(
        "error",
        message
      );
      return value.put("session", state());
    }
  }

  // Called with lifecycle held. Do not acquire serial/connectionLock here:
  // closing the transports interrupts blocked reads without delaying Leave.
  private void resetSession() {
    sessionEpoch++;
    reconnectScheduled = false;
    FutureTask<CodePairing.Handoff> search = lookup;
    lookup = null;
    if (search != null) {
      if (search.isDone() && !search.isCancelled()) try {
        search.get().close();
      } catch (Exception ignored) {}
      search.cancel(true);
    }
    if (codePairing != null) {
      codePairing.close();
      codePairing = null;
    }
    Invitation old = invitation;
    invitation = null;
    role = "Idle";
    transport = "None";
    lanRoute = "";
    lanState = "idle";
    awareState = "idle";
    bleState = "idle";
    vpnActive = false;
    sharedQueue = null;
    allowGuestEdits = true;
    if (nearby != null) {
      nearby.close();
      nearby = null;
    }
    try {
      if (server != null) server.close();
    } catch (Exception ignored) {}
    server = null;
    for (ChannelTransport connection : connections)
      try {
        connection.close();
      } catch (Exception ignored) {}
    connections.clear();
    SecureChannel oldChannel = channel;
    channel = null;
    WarmChannel oldBackup = backupChannel;
    backupChannel = null;
    if (oldBackup != null) {
      try {
        oldBackup.channel.close();
      } catch (Exception ignored) {}
      oldBackup.candidate.close();
    }
    channelAuthorities.clear();
    participants.clear();
    if (oldChannel != null) try {
      oldChannel.close();
    } catch (Exception ignored) {}
    completed.clear();
    payloads.clear();
    if (old != null) old.destroy();
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    end();
    main.removeCallbacksAndMessages(null);
    if (bound) unbindService(binding);
    workers.shutdownNow();
    monitor.shutdownNow();
    active = null;
    super.onDestroy();
  }
}
