package app.morphe.jam.companion;

import android.content.Context;
import android.content.pm.ApplicationInfo;

/** Instrumentation overrides are ignored by non-debuggable builds. */
final class TransportOptions {

  static boolean disabled(Context context, String provider) {
    return (
      (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) !=
        0 &&
      context
        .getSharedPreferences("transport-test", 0)
        .getBoolean("disable" + provider, false)
    );
  }

  static int probeDelay(Context context) {
    if (
      (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) ==
      0
    ) return 2000;
    return Math.max(
      0,
      Math.min(
        10000,
        context
          .getSharedPreferences("transport-test", 0)
          .getInt("activeProbeDelayMs", 2000)
      )
    );
  }
}
