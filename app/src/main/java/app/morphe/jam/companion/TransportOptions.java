package app.morphe.jam.companion;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Process-local instrumentation overrides; never persisted into normal app sessions. */
final class TransportOptions {

  private static volatile Set<String> disabledProviders = Collections.emptySet();
  private static volatile int activeProbeDelayMs = 2000;

  static void configureForTest(Context context, Set<String> disabled, int delayMs) {
    if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
      throw new IllegalStateException("Transport overrides require a debug build");
    activeProbeDelayMs = Math.max(0, Math.min(10000, delayMs));
    disabledProviders = Collections.unmodifiableSet(new HashSet<>(disabled));
  }

  static void resetForTest() {
    disabledProviders = Collections.emptySet();
    activeProbeDelayMs = 2000;
  }

  static boolean disabled(Context context, String provider) {
    return (
      (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) !=
        0 &&
      disabledProviders.contains(provider)
    );
  }

  static int probeDelay(Context context) {
    if (
      (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) ==
      0
    ) return 2000;
    return activeProbeDelayMs;
  }
}
