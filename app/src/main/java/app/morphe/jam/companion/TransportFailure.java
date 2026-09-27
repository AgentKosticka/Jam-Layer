package app.morphe.jam.companion;

import java.net.*;
import java.security.GeneralSecurityException;

enum TransportFailure {
  CONNECT_TIMEOUT,
  TCP_REFUSED,
  HOST_UNREACHABLE,
  NETWORK_LOST,
  AUTH_FAILED,
  CONNECT_FAILED;

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
