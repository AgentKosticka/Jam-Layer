package app.morphe.jam.companion;

import android.net.*;
import android.net.wifi.aware.*;
import android.os.Handler;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.ExecutorService;

/** A single Wi-Fi Aware request. A failed socket always releases this request before retrying. */
final class AwareDataPath implements AutoCloseable {

  interface Listener {
    void onPathAvailable(AwareDataPath path);
    void onSocket(AwareDataPath path, Socket socket);
    void onFailure(AwareDataPath path, String phase, Exception error);
  }

  enum State {
    NEW,
    REQUESTED,
    AVAILABLE,
    PEER_INFO_READY,
    CONNECTING,
    CONNECTED,
    FAILED,
    CLOSED,
  }

  private final ConnectivityManager connectivity;
  private final Handler handler;
  private final ExecutorService workers;
  private final WifiAwareNetworkSpecifier specifier;
  private final int remotePort, timeoutMillis;
  private final boolean connectSocket;
  private final Listener listener;
  private final int generation, attempt;
  private ConnectivityManager.NetworkCallback callback;
  private State state = State.NEW;
  private boolean socketStarted, closed;

  AwareDataPath(
    ConnectivityManager connectivity,
    Handler handler,
    ExecutorService workers,
    WifiAwareNetworkSpecifier specifier,
    int remotePort,
    boolean connectSocket,
    int timeoutMillis,
    int generation,
    int attempt,
    Listener listener
  ) {
    this.connectivity = connectivity;
    this.handler = handler;
    this.workers = workers;
    this.specifier = specifier;
    this.remotePort = remotePort;
    this.connectSocket = connectSocket;
    this.timeoutMillis = timeoutMillis;
    this.generation = generation;
    this.attempt = attempt;
    this.listener = listener;
  }

  int generation() {
    return generation;
  }

  int attempt() {
    return attempt;
  }

  synchronized State state() {
    return state;
  }

  void request() {
    synchronized (this) {
      if (closed || state != State.NEW) return;
      state = State.REQUESTED;
    }
    callback = new ConnectivityManager.NetworkCallback() {
      @Override
      public void onAvailable(Network network) {
        synchronized (AwareDataPath.this) {
          if (closed) return;
          state = State.AVAILABLE;
        }
        listener.onPathAvailable(AwareDataPath.this);
      }

      @Override
      public void onCapabilitiesChanged(
        Network network,
        NetworkCapabilities caps
      ) {
        if (
          !connectSocket ||
          !(caps.getTransportInfo() instanceof WifiAwareNetworkInfo)
        ) return;
        WifiAwareNetworkInfo info =
          (WifiAwareNetworkInfo) caps.getTransportInfo();
        if (info.getPeerIpv6Addr() == null) return;
        if (remotePort <= 0 && info.getPort() == 0) return;
        synchronized (AwareDataPath.this) {
          if (closed || socketStarted) return;
          socketStarted = true;
          state = State.PEER_INFO_READY;
        }
        workers.execute(() -> connect(network, info));
      }

      @Override
      public void onUnavailable() {
        fail("request", new IOException("Aware path unavailable"));
      }

      @Override
      public void onLost(Network network) {
        fail("path", new IOException("Aware path lost"));
      }
    };
    try {
      connectivity.requestNetwork(
        new NetworkRequest.Builder()
          .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
          .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .setNetworkSpecifier(specifier)
          .build(),
        callback,
        handler,
        15000
      );
    } catch (RuntimeException error) {
      fail("request", error);
    }
  }

  private void connect(Network network, WifiAwareNetworkInfo info) {
    Socket socket = null;
    try {
      synchronized (this) {
        if (closed) return;
        state = State.CONNECTING;
      }
      int port = remotePort > 0 ? remotePort : info.getPort();
      socket = network.getSocketFactory().createSocket();
      socket.connect(
        new InetSocketAddress(info.getPeerIpv6Addr(), port),
        timeoutMillis
      );
      synchronized (this) {
        if (closed) {
          socket.close();
          return;
        }
        state = State.CONNECTED;
      }
      listener.onSocket(this, socket);
    } catch (Exception error) {
      try {
        if (socket != null) socket.close();
      } catch (IOException ignored) {}
      fail(
        "socket",
        error instanceof Exception ? (Exception) error : new IOException(error)
      );
    }
  }

  private void fail(String phase, Exception error) {
    synchronized (this) {
      if (closed || state == State.FAILED) return;
      state = State.FAILED;
    }
    android.util.Log.w(
      "MorpheJam",
      "Aware path failure generation=" +
        generation +
        " attempt=" +
        attempt +
        " phase=" +
        phase +
        " " +
        error.getClass().getSimpleName() +
        ": " +
        error.getMessage()
    );
    close();
    listener.onFailure(this, phase, error);
  }

  @Override
  public void close() {
    ConnectivityManager.NetworkCallback value;
    synchronized (this) {
      if (closed) return;
      closed = true;
      state = State.CLOSED;
      value = callback;
      callback = null;
    }
    if (value != null) try {
      connectivity.unregisterNetworkCallback(value);
    } catch (RuntimeException ignored) {}
  }
}
