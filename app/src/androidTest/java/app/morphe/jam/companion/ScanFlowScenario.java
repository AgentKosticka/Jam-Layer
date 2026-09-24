/*
 * Copyright 2026 AgentKosticka.
 * https://github.com/AgentKosticka/Jam-Layer
 */

package app.morphe.jam.companion;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Tests Android's real nested activity-result restoration without changing YTM data. */
final class ScanFlowScenario {

  static void run(Instrumentation runner) throws Exception {
    SharedPreferences pair = runner
      .getTargetContext()
      .getSharedPreferences("pair", 0);
    String oldPackage = pair.getString("package", null),
      oldCap = pair.getString("cap", null);
    String cap =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    Activity parent = null;
    Activity broker = null;
    Activity scan = null;
    Instrumentation.ActivityMonitor brokers = null,
      scanners = null;
    java.util.concurrent.atomic.AtomicReference<Activity> currentBroker =
      new java.util.concurrent.atomic.AtomicReference<>();
    Application app = (Application) runner
      .getTargetContext()
      .getApplicationContext();
    Application.ActivityLifecycleCallbacks lifecycle =
      new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity a, Bundle b) {
          if (
            a instanceof MainActivity &&
            "app.morphe.jam.SCAN".equals(a.getIntent().getAction())
          ) currentBroker.set(a);
        }

        public void onActivityStarted(Activity a) {}

        public void onActivityResumed(Activity a) {}

        public void onActivityPaused(Activity a) {}

        public void onActivityStopped(Activity a) {}

        public void onActivitySaveInstanceState(Activity a, Bundle b) {}

        public void onActivityDestroyed(Activity a) {}
      };
    app.registerActivityLifecycleCallbacks(lifecycle);
    try {
      // The debug companion acts as a temporary caller; restore its pairing afterwards.
      pair
        .edit()
        .putString("package", runner.getTargetContext().getPackageName())
        .putString("cap", cap)
        .commit();
      parent = runner.startActivitySync(
        new Intent(runner.getTargetContext(), MainActivity.class).addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK
        )
      );
      brokers = runner.addMonitor(MainActivity.class.getName(), null, false);
      scanners = runner.addMonitor(ScanActivity.class.getName(), null, false);
      Activity caller = parent;
      runner.runOnMainSync(() ->
        caller.startActivityForResult(
          new Intent(runner.getTargetContext(), MainActivity.class)
            .setAction("app.morphe.jam.SCAN")
            .putExtra("cap", cap),
          99
        )
      );
      broker = runner.waitForMonitorWithTimeout(brokers, 5000);
      scan = runner.waitForMonitorWithTimeout(scanners, 5000);
      if (broker == null || scan == null) throw new AssertionError(
        "Scan flow did not launch"
      );
      Activity original = broker;
      runner.runOnMainSync(original::recreate);
      Thread.sleep(500);
      if (scanners.getHits() != 1) throw new AssertionError(
        "Scanner relaunched on recreation"
      );
      Activity camera = scan;
      String invite = new Invitation().uri();
      runner.runOnMainSync(() -> {
        camera.setResult(
          Activity.RESULT_OK,
          new Intent().putExtra("invite", invite)
        );
        camera.finish();
      });
      for (
        int i = 0;
        i < 50 && currentBroker.get() == original;
        i++
      ) Thread.sleep(100);
      Activity restored = currentBroker.get();
      if (restored == null || restored == original) throw new AssertionError(
        "Broker did not recreate; destroyed=" +
          original.isDestroyed() +
          " finished=" +
          original.isFinishing()
      );
      broker = restored;
      for (int i = 0; i < 50 && !restored.isFinishing(); i++) Thread.sleep(100);
      if (!restored.isFinishing() || scanners.getHits() != 1) {
        throw new AssertionError("Scan result did not finish once");
      }
    } finally {
      app.unregisterActivityLifecycleCallbacks(lifecycle);
      for (Activity activity : new Activity[] { scan, broker, parent }) {
        if (activity != null) runner.runOnMainSync(activity::finish);
      }
      if (brokers != null) runner.removeMonitor(brokers);
      if (scanners != null) runner.removeMonitor(scanners);
      if (JamService.active != null) JamService.active.end();
      pair
        .edit()
        .putString("package", oldPackage)
        .putString("cap", oldCap)
        .commit();
    }
  }
}
