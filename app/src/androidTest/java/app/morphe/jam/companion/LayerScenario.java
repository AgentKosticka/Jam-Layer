package app.morphe.jam.companion;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.*;
import android.os.*;
import app.morphe.jam.ipc.IJamBridge;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Companion-only acceptance tests. The synthetic bridge never touches YTM's queue. */
final class LayerScenario {

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void await(
    BooleanSupplier condition,
    long millis,
    String message
  ) throws Exception {
    long until = SystemClock.elapsedRealtime() + millis;
    while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until)
      Thread.sleep(25);
    check(condition.getAsBoolean(), message);
  }

  private static JSONObject command(String op) throws Exception {
    return new JSONObject()
      .put("op", op)
      .put("id", UUID.randomUUID().toString());
  }

  private static Field field(String name) throws Exception {
    Field value = JamService.class.getDeclaredField(name);
    value.setAccessible(true);
    return value;
  }

  private static boolean notification(Context context) {
    for (android.service.notification.StatusBarNotification n : context
      .getSystemService(NotificationManager.class)
      .getActiveNotifications())
      if (n.getId() == 41) return true;
    return false;
  }

  private static final class Bridge extends IJamBridge.Stub {

    volatile boolean playing;
    volatile CountDownLatch entered, release;

    public String call(String cap, String request) {
      try {
        JSONObject r = new JSONObject(request);
        CountDownLatch wait = release;
        if (wait != null) {
          entered.countDown();
          check(wait.await(10, TimeUnit.SECONDS), "Synthetic bridge timed out");
        }
        if ("PLAY".equals(r.optString("op"))) playing = r.getBoolean("playing");
        return new JSONObject()
          .put("ok", true)
          .put("revision", "fixture:" + playing)
          .put(
            "items",
            new JSONArray().put(
              new JSONObject()
                .put("id", "1")
                .put("videoId", "abcdefghijk")
                .put("title", "Companion fixture")
                .put("current", true)
            )
          )
          .put("autoplay", new JSONArray())
          .put(
            "clock",
            new JSONObject()
              .put("playing", playing)
              .put("position", 0)
              .put("duration", 60000)
          )
          .toString();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  static void run(Instrumentation test, Bundle args) throws Exception {
    Context context = test.getTargetContext();
    // Wiped test installation has no user pairing. Supply only a temporary start capability.
    check(
      !context.getSharedPreferences("pair", 0).contains("package"),
      "Use a wiped companion for LayerScenario"
    );
    context
      .getSharedPreferences("pair", 0)
      .edit()
      .putString("cap", UUID.randomUUID().toString() + UUID.randomUUID())
      .commit();
    CountDownLatch bound = new CountDownLatch(1);
    ServiceConnection binding = new ServiceConnection() {
      public void onServiceConnected(ComponentName name, IBinder binder) {
        bound.countDown();
      }

      public void onServiceDisconnected(ComponentName name) {}
    };
    context.startActivity(
      new Intent(context, MainActivity.class).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK
      )
    );
    test.waitForIdleSync();
    context.bindService(
      new Intent(context, JamService.class),
      binding,
      Context.BIND_AUTO_CREATE
    );
    check(bound.await(5, TimeUnit.SECONDS), "Companion binding unavailable");
    JamService service = JamService.active;
    Bridge bridge = new Bridge();
    field("bridge").set(service, bridge);
    ExecutorService tasks = Executors.newCachedThreadPool();
    try {
      context.startForegroundService(JamService.startIntent(context));
      await(
        () -> notification(context),
        3000,
        "Foreground notification missing"
      );
      String role = args.getString("role");
      String mode = args.getString("transport", "LAN");
      if ("layerHost".equals(role)) {
        service.host(mode);
        JSONObject invite = service.dispatch(command("INVITE"));
        Files.write(
          context.getFilesDir().toPath().resolve("layer-invite.txt"),
          service.invite().getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
          context.getFilesDir().toPath().resolve("layer-code.txt"),
          invite.getString("code").getBytes(StandardCharsets.UTF_8)
        );
        Bundle ready = new Bundle();
        ready.putString("stream", "LAYER_HOST_READY\n");
        test.sendStatus(0, ready);
        java.nio.file.Path stop = context
          .getFilesDir()
          .toPath()
          .resolve("layer-stop");
        Files.deleteIfExists(stop);
        await(() -> Files.exists(stop), 150000, "Guest did not finish");
      } else if ("layerGuest".equals(role)) {
        long started = SystemClock.elapsedRealtime();
        JSONObject joined = service.dispatch(
          command("JOIN")
            .put("invite", args.getString("invite"))
            .put("transport", mode)
        );
        check(
          joined.optBoolean("ok"),
          "Join failed: " + joined.optString("error")
        );
        await(
          () ->
            "Authenticated host connected".equals(
              service.state().optString("message")
            ),
          45000,
          "Host not connected: " + service.state()
        );
        long connected = SystemClock.elapsedRealtime() - started;
        JSONObject snapshot = service.dispatch(command("SNAPSHOT"));
        check(
          snapshot.optBoolean("ok") &&
            snapshot.getJSONArray("items").length() == 1,
          "Snapshot missing"
        );
        JSONObject play = command("PLAY").put("playing", true);
        check(service.dispatch(play).optBoolean("ok"), "Remote command failed");
        check(
          service.dispatch(play).optBoolean("ok"),
          "Duplicate command failed"
        );
        check(
          service
            .dispatch(command("SNAPSHOT"))
            .getJSONObject("clock")
            .getBoolean("playing"),
          "Host state did not converge"
        );
        SecureChannel previous = (SecureChannel) field("channel").get(service);
        previous.close();
        await(
          () -> {
            try {
              Object next = field("channel").get(service);
              return next != null && next != previous;
            } catch (Exception e) {
              return false;
            }
          },
          45000,
          "Transport did not reconnect"
        );
        check(
          service.dispatch(command("SNAPSHOT")).optBoolean("ok"),
          "Recovered snapshot failed"
        );
        Bundle progress = new Bundle();
        progress.putString(
          "stream",
          "PASS " +
            mode +
            " join=" +
            connected +
            "ms, command, duplicate, snapshot, reconnect\n"
        );
        test.sendStatus(0, progress);
      } else {
        // A blocked native snapshot must neither delay Leave nor republish after it.
        bridge.entered = new CountDownLatch(1);
        bridge.release = new CountDownLatch(1);
        JSONObject host = service.dispatch(
          command("HOST").put("transport", "LAN")
        );
        check(
          "Host".equals(host.getJSONObject("session").getString("role")),
          "HOST did not return its state"
        );
        check(
          bridge.entered.await(4, TimeUnit.SECONDS),
          "Snapshot did not start"
        );
        long started = SystemClock.elapsedRealtime();
        JSONObject ended = service.dispatch(command("END"));
        check(
          SystemClock.elapsedRealtime() - started < 750,
          "Leave waited for native work"
        );
        check(
          "Idle".equals(ended.getJSONObject("session").getString("role")),
          "END did not return Idle"
        );
        bridge.release.countDown();
        bridge.release = null;
        await(
          () -> !notification(context),
          2000,
          "Leave retained foreground notification"
        );
        Thread.sleep(600);
        check(
          !service.dispatch(command("VIEW")).has("items"),
          "Old snapshot resurrected after Leave"
        );
        check(
          !((PowerManager.WakeLock) field("wakeLock").get(service)).isHeld(),
          "Leave retained wake lock"
        );

        context.startForegroundService(JamService.startIntent(context));
        Future<JSONObject> joining = tasks.submit(() ->
          service.dispatch(
            command("JOIN").put("invite", "ABCDEFGH").put("transport", "LAN")
          )
        );
        await(
          () -> "Joining".equals(service.state().optString("role")),
          2000,
          "Code search did not start"
        );
        service.end();
        check(
          !joining.get(2, TimeUnit.SECONDS).optBoolean("ok"),
          "Cancelled search succeeded"
        );
        check(
          field("lookup").get(service) == null,
          "Lookup retained after cancellation"
        );
        context.startForegroundService(JamService.startIntent(context));
        service.host("LAN");
        Thread.sleep(1800);
        check(
          "Host".equals(service.state().optString("role")) &&
            notification(context),
          "Idle cleanup stopped replacement session"
        );
        Invitation current = (Invitation) field("invitation").get(service);
        Field expires = Invitation.class.getDeclaredField("expires");
        expires.setAccessible(true);
        expires.setLong(current, System.currentTimeMillis() - 1);
        await(
          () -> "Idle".equals(service.state().optString("role")),
          2000,
          "Expired session remained active"
        );
        await(
          () -> !notification(context),
          2000,
          "Expiry retained notification"
        );
        context.startForegroundService(JamService.startIntent(context));
        await(
          () -> notification(context),
          1000,
          "Idle start missing notification"
        );
        await(
          () -> !notification(context),
          2500,
          "Pairing-only start retained notification"
        );
      }
      service.end();
      await(
        () -> !notification(context),
        2000,
        "Final Leave retained notification"
      );
      check(service.invite().isEmpty(), "Invitation survived Leave");
      check(
        ((java.util.Set<?>) field("connections").get(service)).isEmpty(),
        "Connections survived Leave"
      );
      check(
        !((PowerManager.WakeLock) field("wakeLock").get(service)).isHeld(),
        "Wake lock survived Leave"
      );
    } finally {
      if (bridge.release != null) bridge.release.countDown();
      service.end();
      tasks.shutdownNow();
      context.unbindService(binding);
      context.getSharedPreferences("pair", 0).edit().clear().commit();
    }
  }
}
