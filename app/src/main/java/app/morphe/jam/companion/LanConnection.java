package app.morphe.jam.companion;

import android.net.Network;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import java.io.IOException;
import java.net.*;
import java.util.*;
import javax.net.SocketFactory;

/** Route LAN sockets deliberately; never process-bind the app away from a VPN. */
final class LanConnection {

  private LanConnection() {}

  enum Route {
    BOUND_ENDPOINT_NETWORK,
    BOUND_PHYSICAL_FALLBACK,
    SYSTEM_ROUTED_FALLBACK,
  }

  static final class Result {

    final Socket socket;
    final Route route;

    Result(Socket socket, Route route) {
      this.socket = socket;
      this.route = route;
    }
  }

  static Socket connect(NsdServiceInfo service, int timeoutMillis)
    throws IOException {
    Network network = Build.VERSION.SDK_INT >= 33 ? service.getNetwork() : null;
    List<InetAddress> addresses =
      Build.VERSION.SDK_INT >= 34
        ? service.getHostAddresses()
        : Collections.singletonList(service.getHost());
    SocketFactory factory =
      network == null ? SocketFactory.getDefault() : network.getSocketFactory();
    return connect(addresses, service.getPort(), timeoutMillis, factory);
  }

  static Result connect(
    LanEndpoint endpoint,
    LocalNetworkTracker.Snapshot topology,
    int timeoutMillis
  ) throws IOException {
    long deadline = deadline(timeoutMillis);
    List<IOException> failures = new ArrayList<>();
    if (endpoint.network != null) {
      Socket socket = attempt(
        endpoint.addresses,
        endpoint.port,
        deadline,
        endpoint.network.getSocketFactory(),
        failures
      );
      if (socket != null) return new Result(
        socket,
        Route.BOUND_ENDPOINT_NETWORK
      );
      // A system route is only a compatibility fallback for a VPN's own LAN policy.
      if (topology != null && topology.vpnActive) {
        socket = attempt(
          endpoint.addresses,
          endpoint.port,
          deadline,
          SocketFactory.getDefault(),
          failures
        );
        if (socket != null) return new Result(
          socket,
          Route.SYSTEM_ROUTED_FALLBACK
        );
      }
    } else {
      if (topology != null) for (Network network : topology.localNetworks) {
        Socket socket = attempt(
          endpoint.addresses,
          endpoint.port,
          deadline,
          network.getSocketFactory(),
          failures
        );
        if (socket != null) return new Result(
          socket,
          Route.BOUND_PHYSICAL_FALLBACK
        );
        if (remaining(deadline) <= 0) break;
      }
      Socket socket = attempt(
        endpoint.addresses,
        endpoint.port,
        deadline,
        SocketFactory.getDefault(),
        failures
      );
      if (socket != null) return new Result(
        socket,
        Route.SYSTEM_ROUTED_FALLBACK
      );
    }
    throw failure(failures);
  }

  static Socket connect(
    List<InetAddress> addresses,
    int port,
    int timeoutMillis,
    SocketFactory factory
  ) throws IOException {
    List<IOException> failures = new ArrayList<>();
    Socket socket = attempt(
      addresses,
      port,
      deadline(timeoutMillis),
      factory,
      failures
    );
    if (socket != null) return socket;
    throw failure(failures);
  }

  /** Testable route order without Android Network instances. */
  static Result connectWithFactories(
    List<InetAddress> addresses,
    int port,
    int timeoutMillis,
    List<SocketFactory> physicalFactories,
    SocketFactory systemFactory
  ) throws IOException {
    long deadline = deadline(timeoutMillis);
    List<IOException> failures = new ArrayList<>();
    for (SocketFactory factory : physicalFactories) {
      Socket socket = attempt(addresses, port, deadline, factory, failures);
      if (socket != null) return new Result(
        socket,
        Route.BOUND_PHYSICAL_FALLBACK
      );
      if (remaining(deadline) <= 0) break;
    }
    Socket socket = attempt(addresses, port, deadline, systemFactory, failures);
    if (socket != null) return new Result(socket, Route.SYSTEM_ROUTED_FALLBACK);
    throw failure(failures);
  }

  private static Socket attempt(
    List<InetAddress> addresses,
    int port,
    long deadline,
    SocketFactory factory,
    List<IOException> failures
  ) {
    if (addresses == null || factory == null) return null;
    for (InetAddress address : addresses) {
      if (address == null) continue;
      if (remaining(deadline) <= 0) {
        failures.add(
          new SocketTimeoutException("LAN connection budget exhausted")
        );
        return null;
      }
      Socket socket = null;
      try {
        socket = factory.createSocket();
        socket.connect(
          new InetSocketAddress(address, port),
          remaining(deadline)
        );
        return socket;
      } catch (IOException error) {
        failures.add(error);
        try {
          if (socket != null) socket.close();
        } catch (IOException ignored) {}
      }
    }
    return null;
  }

  private static long deadline(int timeoutMillis) {
    return System.nanoTime() + Math.max(1, timeoutMillis) * 1_000_000L;
  }

  private static int remaining(long deadline) {
    return (int) Math.min(
      Integer.MAX_VALUE,
      Math.max(0, (deadline - System.nanoTime() + 999_999L) / 1_000_000L)
    );
  }

  private static IOException failure(List<IOException> failures) {
    IOException result = new IOException(
      "LAN service has no reachable address"
    );
    for (IOException error : failures) result.addSuppressed(error);
    return result;
  }
}
