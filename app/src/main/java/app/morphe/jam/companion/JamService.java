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
  private String selectedMode = "Auto";
  private volatile JSONObject sharedQueue;
  private volatile long discoveryStarted;
  private volatile boolean allowGuestEdits = true;
  private final Object serial = new Object(),
    connectionLock = new Object();
  private final Map<String, String> completed = new HashMap<>(),
    payloads = new HashMap<>();
  private final Set<ChannelTransport> connections = ConcurrentHashMap.newKeySet();
  private volatile IJamBridge bridge;
  private volatile Invitation invitation;
  private volatile SecureChannel channel;
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
      message = "YouTube Music connected";
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
      new Handler(getMainLooper()).postDelayed(() -> bindMusic(), 1000);
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
          end();
          message = "Session expired";
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
    return START_NOT_STICKY;
  }

  public JSONObject state() {
    try {
      return ok()
        .put("role", role)
        .put("transport", transport)
        .put("message", message)
        .put("paired", bridge != null)
        .put("peers", connections.size())
        .put("allowGuestEdits", allowGuestEdits)
        .put("lanState", lanState)
        .put("awareState", awareState)
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
    end();
    long epoch = sessionEpoch;
    role = "Joining";
    message = "Finding the code on nearby devices…";
    try {
      String value = CodePairing.find(this, code);
      if (epoch != sessionEpoch) return error("Joining cancelled");
      join(value, mode);
      return ok();
    } catch (Exception e) {
      if (epoch == sessionEpoch) {
        role = "Idle";
        message = e.getMessage();
      }
      throw e;
    }
  }

  public void host(String mode) {
    end();
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
        server = listening;
        nearby = new Nearby(
          this,
          next,
          true,
          listening.getLocalPort(),
          mode,
          listener
        );
        nearby.start();
        message = "Host ready; share the QR invitation";
        synchronized (serial) {
          snapshotHost(next);
        }
        while (invitation == next && next.valid()) {
          Socket socket = listening.accept();
          if (connections.size() >= 8) {
            socket.close();
            continue;
          }
          try {
            ChannelTransport connection = new SocketChannelTransport(socket);
            connections.add(connection);
            workers.execute(() -> serve(connection, next, null));
          } catch (Exception error) {
            try { socket.close(); } catch (Exception ignored) {}
          }
        }
      } catch (Exception e) {
        if (invitation == next) {
          android.util.Log.e("MorpheJam", "Host failed", e);
          message = "Host stopped: " + e.getClass().getSimpleName();
        }
      }
    });
  }

  public void join(String value, String mode) {
    final Invitation next = new Invitation(value);
    end();
    invitation = next;
    role = "Participant";
    selectedMode = mode;
    transport = "Connecting";
    wakeLock.acquire(Math.max(1, next.expires - System.currentTimeMillis()));
    message = "Discovering host";
    discoveryStarted = System.currentTimeMillis();
    nearby = new Nearby(this, next, false, 0, mode, listener);
    nearby.start();
  }

  private void reconnect(Invitation expected) {
    final long epoch = sessionEpoch;
    if (reconnectScheduled) return;
    reconnectScheduled = true;
    monitor.schedule(
      () -> {
        if (sessionEpoch != epoch) return;
        reconnectScheduled = false;
        if (
          invitation != expected || sessionEpoch != epoch || channel != null
        ) return;
        if (nearby != null) nearby.resume();
        else {
          nearby = new Nearby(this, expected, false, 0, selectedMode, listener);
          nearby.start();
        }
      },
      500,
      TimeUnit.MILLISECONDS
    );
  }

  private final Nearby.Listener listener = new Nearby.Listener() {
    public void status(String m) {
      message = m;
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
      if (
        expected == null || !expected.valid()
      ) {
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
        try {
          authenticated = new SecureChannel(
            offered.connection(),
            false,
            expected.jamId,
            expected.secret,
            identity
          );
        } catch (Exception error) {
          offered.reject();
          if (
            invitation == expected && sessionEpoch == epoch && channel == null
          ) message =
            "Host authentication failed; waiting for another transport";
          return;
        }
        boolean won = false;
        synchronized (connectionLock) {
          if (
            invitation == expected &&
            sessionEpoch == epoch &&
            expected.valid() &&
            "Participant".equals(role) &&
            channel == null &&
            offered.accept()
          ) {
            channel = authenticated;
            connections.add(offered.connection());
            transport = displayTransport(offered);
            lanRoute = offered.route();
            message = "Authenticated host connected";
            won = true;
            android.util.Log.i(
              "MorpheJam",
              "Authenticated transport=" +
                offered.transport() +
                " route=" +
                offered.route()
            );
          }
        }
        if (!won) {
          try {
            authenticated.close();
          } catch (Exception ignored) {}
          offered.reject();
          return;
        }
        workers.execute(() -> sync(expected));
      });
    }
  };

  /** Existing music patches recognize LAN and Aware; the route retains the precise medium. */
  private static String displayTransport(Nearby.ConnectionCandidate candidate) {
    return "Nearby".equals(candidate.transport()) ? "Aware" : candidate.transport();
  }

  private void serve(
    ChannelTransport connection,
    Invitation session,
    Nearby.ConnectionCandidate offered
  ) {
    try (
      SecureChannel peer = new SecureChannel(
        connection,
        true,
        session.jamId,
        session.secret,
        null
      )
    ) {
      if (offered != null && !offered.accept()) return;
      if (offered != null) {
        connections.add(connection);
        transport = displayTransport(offered);
        lanRoute = offered.route();
      }
      android.util.Log.i("MorpheJam", "Authenticated participant");
      message = "Participant authenticated";
      while (invitation == session && session.valid()) {
        String request = peer.receive();
        JSONObject command = new JSONObject(request);
        String op = command.optString("op");
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
        peer.send(hostCall(command, peer.clientId).toString());
      }
    } catch (Exception e) {
      android.util.Log.i(
        "MorpheJam",
        "Participant disconnected: " + e.getClass().getSimpleName()
      );
    } finally {
      connections.remove(connection);
      try {
        connection.close();
      } catch (Exception ignored) {}
      if (offered != null) offered.close();
    }
  }

  private JSONObject hostCall(JSONObject request, String client)
    throws Exception {
    synchronized (serial) {
      String op = request.optString("op");
      if ("SNAPSHOT".equals(op)) return snapshotHost(invitation);
      if (!identity.equals(client) && !allowGuestEdits) return error(
        "The host has locked guest edits"
      );
      String id = request.getString("id");
      if (!UUID.fromString(id).toString().equals(id)) return error(
        "Invalid command id"
      );
      String key = client + ":" + id,
        body = request.toString();
      if (payloads.containsKey(key)) {
        if (!payloads.get(key).equals(body)) return error("Command id reused");
        return new JSONObject(completed.get(key));
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
      if (response.optBoolean("ok") && response.has("items")) sharedQueue =
        response;
      completed.put(key, response.toString());
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
        sharedQueue = value;
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
      if (!value.optBoolean("ok") && !value.optBoolean("unchanged")) {
        String failure = value.optString("error");
        if (!failure.isEmpty()) message = failure;
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
      return ok();
    }
    if ("JOIN".equals(request.optString("op"))) {
      String value = request.getString("invite").trim();
      if (!value.startsWith("morphejam://")) return joinCode(
        value,
        request.optString("transport", "Auto")
      );
      join(value, request.optString("transport", "Auto"));
      return ok();
    }
    if ("INVITE".equals(request.optString("op"))) {
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
      for (int y = 0; y < 600; y++) for (
        int x = 0;
        x < 600;
        x++
      ) bitmap.setPixel(x, y, bits.get(x, y) ? 0xff000000 : 0xffffffff);
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
      synchronized (serial) {
        try {
          if (codePairing == null || !codePairing.valid()) {
            if (codePairing != null) codePairing.close();
            codePairing = new CodePairing(this, invitation);
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
      JSONObject view =
        sharedQueue == null
          ? error(
              "Participant".equals(role) &&
                !"Authenticated host connected".equals(message)
                ? message
                : "Waiting for the host queue"
            )
          : new JSONObject(sharedQueue.toString());
      return view.put("session", state());
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
      return ok();
    }
    Invitation current = invitation;
    if (current == null || !current.valid()) return error(
      "No active Jam session"
    );
    if ("Host".equals(role)) return hostCall(request, identity);
    synchronized (connectionLock) {
      if (channel == null) return error("Host is not connected");
      try {
        // Queue synchronization is a heartbeat. Detect a dead route promptly;
        // leave longer native queue edits their existing completion budget.
        channel.setReadTimeout(
          "SYNC".equals(request.optString("op")) ? 8000 : 45000
        );
        long sent = SystemClock.elapsedRealtime();
        channel.send(request.toString());
        JSONObject value = new JSONObject(channel.receive());
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
        return value;
      } catch (Exception e) {
        android.util.Log.w("MorpheJam", "Host channel failed", e);
        try {
          channel.close();
        } catch (Exception ignored) {}
        channel = null;
        connections.removeIf(ChannelTransport::isClosed);
        transport = "Disconnected";
        message = "Reconnecting to host";
        reconnect(current);
        return error("Outcome unknown; refresh before editing. Reconnecting.");
      }
    }
  }

  public void end() {
    sessionEpoch++;
    reconnectScheduled = false;
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
    synchronized (connectionLock) {
      if (channel != null) try {
        channel.close();
      } catch (Exception ignored) {}
      channel = null;
    }
    synchronized (serial) {
      completed.clear();
      payloads.clear();
    }
    if (old != null) old.destroy();
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
  }

  @Override
  public void onDestroy() {
    end();
    if (bound) unbindService(binding);
    workers.shutdownNow();
    monitor.shutdownNow();
    active = null;
    super.onDestroy();
  }
}
