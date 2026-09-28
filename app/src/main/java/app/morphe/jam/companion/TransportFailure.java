package app.morphe.jam.companion;

import java.net.*;
import java.security.GeneralSecurityException;

enum TransportFailure {
  DISCOVERY_TIMEOUT, ENDPOINT_STALE, NETWORK_CHANGED, VPN_ROUTE_BLOCKED,
  AWARE_UNAVAILABLE, AWARE_ATTACH_FAILED, AWARE_DISCOVERY_STALLED,
  AWARE_PATH_FAILED, AWARE_SOCKET_FAILED,
  BLE_UNAVAILABLE, BLE_PERMISSION_MISSING, BLE_PAUSED_FOR_AUDIO,
  BLE_SCAN_FAILED, BLE_ADVERTISE_FAILED, BLE_L2CAP_FAILED,
  PROTOCOL_MISMATCH, SESSION_EXPIRED,
  CONNECT_TIMEOUT,
  TCP_REFUSED,
  HOST_UNREACHABLE,
  NETWORK_LOST,
  AUTH_FAILED,
  CONNECT_FAILED;

  long retryDelay(int attempt) {
    if (this == BLE_PAUSED_FOR_AUDIO || this == BLE_PERMISSION_MISSING ||
        this == SESSION_EXPIRED || this == PROTOCOL_MISMATCH) return -1;
    long base = this == AUTH_FAILED ? 3000 :
      this == AWARE_UNAVAILABLE || this == BLE_UNAVAILABLE ? 3000 : 500;
    return Math.min(30000, base * (1L << Math.min(5, Math.max(0, attempt - 1))));
  }

  static TransportFailure ble(String status) {
    String s = status.toLowerCase(java.util.Locale.ROOT);
    if (s.contains("audio")) return BLE_PAUSED_FOR_AUDIO;
    if (s.contains("permission")) return BLE_PERMISSION_MISSING;
    if (s.contains("scan") && s.contains("unavailable")) return BLE_SCAN_FAILED;
    if (s.contains("advertising") && (s.contains("unavailable") || s.contains("unsupported"))) return BLE_ADVERTISE_FAILED;
    if (s.contains("l2cap") || s.contains("connection retry")) return BLE_L2CAP_FAILED;
    if (s.contains("unavailable") || s.contains("unsupported") || s.contains("off") || s.contains("requires")) return BLE_UNAVAILABLE;
    return null;
  }

  static TransportFailure classify(Throwable error) {
    if (error instanceof GeneralSecurityException) return AUTH_FAILED;
    if (error instanceof SocketTimeoutException) return CONNECT_TIMEOUT;
    if (error instanceof ConnectException) return TCP_REFUSED;
    if (
      error instanceof NoRouteToHostException ||
      error instanceof UnknownHostException
    ) return HOST_UNREACHABLE;
    if (error instanceof SocketException) return NETWORK_LOST;
    if (error.getCause() != null && error.getCause() != error) {
      TransportFailure nested = classify(error.getCause());
      if (nested != CONNECT_FAILED) return nested;
    }
    for (Throwable nested : error.getSuppressed()) {
      TransportFailure result = classify(nested);
      if (result != CONNECT_FAILED) return result;
    }
    return CONNECT_FAILED;
  }
}
