package app.morphe.jam.companion;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** Tracks physical local networks independently of the process default/VPN route. */
final class LocalNetworkTracker implements AutoCloseable {

  interface Listener {
    void onTopologyChanged(Snapshot snapshot);
  }

  static final class Snapshot {

    final List<Network> localNetworks;
    final boolean vpnActive;
    final Network defaultNetwork;
    final long generation;

    Snapshot(
      List<Network> localNetworks,
      boolean vpnActive,
      Network defaultNetwork,
      long generation
    ) {
      this.localNetworks = Collections.unmodifiableList(
        new ArrayList<>(localNetworks)
      );
      this.vpnActive = vpnActive;
      this.defaultNetwork = defaultNetwork;
      this.generation = generation;
    }
  }

  private final ConnectivityManager connectivity;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Object lock = new Object();
  private final Map<Long, Network> locals = new HashMap<>();
  private final Set<Network> vpns = new HashSet<>();
  private final Set<Listener> listeners = new HashSet<>();
  private Network defaultNetwork;
  private long generation;
  private boolean started, closed;
  private final ConnectivityManager.NetworkCallback localCallback =
    new ConnectivityManager.NetworkCallback() {
      @Override
      public void onAvailable(Network network) {
        refreshLocal(network);
      }

      @Override
      public void onCapabilitiesChanged(
        Network network,
        NetworkCapabilities caps
      ) {
        refreshLocal(network, caps);
      }

      @Override
      public void onLost(Network network) {
        synchronized (lock) {
          locals.remove(network.getNetworkHandle());
        }
        publish();
      }
    };
  private final ConnectivityManager.NetworkCallback vpnCallback =
    new ConnectivityManager.NetworkCallback() {
      @Override
      public void onAvailable(Network network) {
        synchronized (lock) {
          vpns.add(network);
        }
        publish();
      }

      @Override
      public void onLost(Network network) {
        synchronized (lock) {
          vpns.remove(network);
        }
        publish();
      }

      @Override
      public void onCapabilitiesChanged(
        Network network,
        NetworkCapabilities caps
      ) {
        synchronized (lock) {
          if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpns.add(
            network
          );
          else vpns.remove(network);
        }
        publish();
      }
    };
  private final ConnectivityManager.NetworkCallback defaultCallback =
    new ConnectivityManager.NetworkCallback() {
      @Override
      public void onAvailable(Network network) {
        synchronized (lock) {
          defaultNetwork = network;
        }
        publish();
      }

      @Override
      public void onLost(Network network) {
        synchronized (lock) {
          if (network.equals(defaultNetwork)) defaultNetwork = null;
        }
        publish();
      }

      @Override
      public void onCapabilitiesChanged(
        Network network,
        NetworkCapabilities caps
      ) {
        synchronized (lock) {
          defaultNetwork = network;
          if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpns.add(
            network
          );
        }
        publish();
      }
    };

  LocalNetworkTracker(Context context) {
    connectivity = context
      .getApplicationContext()
      .getSystemService(ConnectivityManager.class);
  }

  void start() {
    if (connectivity == null || started || closed) return;
    started = true;
    try {
      NetworkRequest physical = new NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build();
      connectivity.registerNetworkCallback(physical, localCallback, handler);
      NetworkRequest vpn = new NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .build();
      connectivity.registerNetworkCallback(vpn, vpnCallback, handler);
      connectivity.registerDefaultNetworkCallback(defaultCallback, handler);
      for (Network network : connectivity.getAllNetworks()) {
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        if (caps != null) {
          refreshLocal(network, caps);
          if (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
          ) synchronized (lock) {
            vpns.add(network);
          }
        }
      }
      publish();
    } catch (RuntimeException error) {
      android.util.Log.w(
        "MorpheJam",
        "Network topology monitoring unavailable",
        error
      );
    }
  }

  void addListener(Listener listener) {
    synchronized (lock) {
      listeners.add(listener);
    }
    listener.onTopologyChanged(snapshot());
  }

  void removeListener(Listener listener) {
    synchronized (lock) {
      listeners.remove(listener);
    }
  }

  Snapshot snapshot() {
    synchronized (lock) {
      return new Snapshot(
        new ArrayList<>(locals.values()),
        !vpns.isEmpty(),
        defaultNetwork,
        generation
      );
    }
  }

  private void refreshLocal(Network network) {
    refreshLocal(
      network,
      connectivity == null ? null : connectivity.getNetworkCapabilities(network)
    );
  }

  private void refreshLocal(Network network, NetworkCapabilities caps) {
    synchronized (lock) {
      if (
        caps != null &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
          caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
      ) {
        locals.put(network.getNetworkHandle(), network);
      } else locals.remove(network.getNetworkHandle());
    }
    publish();
  }

  private void publish() {
    final Snapshot snapshot;
    final List<Listener> targets;
    synchronized (lock) {
      if (closed) return;
      snapshot = new Snapshot(
        new ArrayList<>(locals.values()),
        !vpns.isEmpty(),
        defaultNetwork,
        ++generation
      );
      targets = new ArrayList<>(listeners);
    }
    for (Listener listener : targets) listener.onTopologyChanged(snapshot);
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      listeners.clear();
      locals.clear();
      vpns.clear();
    }
    if (connectivity != null && started) {
      try {
        connectivity.unregisterNetworkCallback(localCallback);
      } catch (RuntimeException ignored) {}
      try {
        connectivity.unregisterNetworkCallback(vpnCallback);
      } catch (RuntimeException ignored) {}
      try {
        connectivity.unregisterNetworkCallback(defaultCallback);
      } catch (RuntimeException ignored) {}
    }
  }
}
