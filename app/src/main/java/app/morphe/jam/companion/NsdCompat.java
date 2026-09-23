package app.morphe.jam.companion;

import android.os.Build;
import android.os.ext.SdkExtensions;

/** Keeps NSD platform and extension checks out of transport code. */
final class NsdCompat {

  private NsdCompat() {}

  static int tiramisuExtension() {
    if (Build.VERSION.SDK_INT < 30) return 0;
    try {
      return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU);
    } catch (RuntimeException ignored) {
      return 0;
    }
  }

  static boolean supportsNetworkScopedDiscovery() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
  }

  /* ServiceInfoCallback was added after the API 33 network-scoped overload. */
  static boolean supportsServiceInfoCallbacks() {
    return Build.VERSION.SDK_INT >= 34;
  }
}
