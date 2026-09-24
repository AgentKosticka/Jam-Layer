package app.morphe.jam.companion;

import android.content.Context;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.Executor;

/** Network-scoped NSD, with immediate legacy resolution on old Android releases. */
final class LanBrowser implements AutoCloseable, LocalNetworkTracker.Listener {

  interface Listener {
    void onEndpoint(LanEndpoint endpoint);
    void onServiceLost(String serviceName, long networkHandle);
    void onLanStatus(String status);
  }

  private static final class Discovery {

    final Network network;
    final NsdManager.DiscoveryListener listener;

    Discovery(Network network, NsdManager.DiscoveryListener listener) {
      this.network = network;
      this.listener = listener;
    }
  }

  private final NsdManager nsd;
  private final Context context;
  private final LocalNetworkTracker tracker;
  private final String type;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Executor executor = command -> handler.post(command);
  private final Map<Long, Discovery> discoveries = new HashMap<>();
  private final Map<String, NsdManager.ServiceInfoCallback> callbacks =
    new HashMap<>();
  private final Map<String, String> discoveredServices = new HashMap<>();
  private final Map<String, Integer> legacyRetries = new HashMap<>();
  private final Set<String> resolving = new HashSet<>();
  private final Map<Long, Integer> discoveryFailures = new HashMap<>();
  private final Map<Long, Runnable> discoveryRetries = new HashMap<>();
  private final WifiManager.MulticastLock multicastLock;
  private LanProbe.Browser probeBrowser;
  private boolean closed, legacyStarted;

  LanBrowser(
    Context context,
    LocalNetworkTracker tracker,
    String type,
    Listener listener
  ) {
    Context app = context.getApplicationContext();
    this.context = app;
    nsd = app.getSystemService(NsdManager.class);
    this.tracker = tracker;
    this.type = type;
    this.listener = listener;
    WifiManager wifi = app.getSystemService(WifiManager.class);
    multicastLock =
      wifi == null ? null : wifi.createMulticastLock("MorpheJam:nsd");
    if (multicastLock != null) multicastLock.setReferenceCounted(false);
  }

  void start() {
    if (closed || probeBrowser != null) return;
    probeBrowser = new LanProbe.Browser(
      context,
      tracker,
      type,
      listener::onEndpoint
    );
    probeBrowser.start();
    if (closed || nsd == null) {
      listener.onLanStatus("LAN discovery unavailable");
      return;
    }
    if (multicastLock != null) try {
      multicastLock.acquire();
    } catch (RuntimeException ignored) {}
    tracker.addListener(this);
  }

  @Override
  public void onTopologyChanged(LocalNetworkTracker.Snapshot topology) {
    handler.post(() -> {
      if (closed) return;
      // The default route can expose peers that a physical-network-scoped
      // browse cannot (for example, a VPN that carries local discovery).
      if (!legacyStarted) startDiscovery(-1, null);
      if (!NsdCompat.supportsNetworkScopedDiscovery()) return;
      Set<Long> wanted = new HashSet<>();
      for (Network network : topology.localNetworks) {
        long handle = network.getNetworkHandle();
        wanted.add(handle);
        if (!discoveries.containsKey(handle)) startDiscovery(handle, network);
      }
      for (Long handle : new ArrayList<>(discoveries.keySet()))
        if (handle != -1 && !wanted.contains(handle)) stopDiscovery(handle);
    });
  }

  private void startDiscovery(long handle, Network network) {
    NsdManager.DiscoveryListener discovery =
      new NsdManager.DiscoveryListener() {
        @Override
        public void onDiscoveryStarted(String ignored) {
          discoveryFailures.remove(handle);
          listener.onLanStatus("LAN discovering");
        }

        @Override
        public void onDiscoveryStopped(String ignored) {
          if (removeDiscovery(handle, this)) scheduleRetry(handle);
        }

        @Override
        public void onStartDiscoveryFailed(String ignored, int error) {
          if (removeDiscovery(handle, this)) {
            listener.onLanStatus("LAN discovery failed: " + error);
            scheduleRetry(handle);
          }
        }

        @Override
        public void onStopDiscoveryFailed(String ignored, int error) {
          if (removeDiscovery(handle, this)) scheduleRetry(handle);
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
          lost(info, network);
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
          found(info, network);
        }
      };
    try {
      discoveries.put(handle, new Discovery(network, discovery));
      if (handle == -1) legacyStarted = true;
      if (network == null) nsd.discoverServices(
        type,
        NsdManager.PROTOCOL_DNS_SD,
        discovery
      );
      else nsd.discoverServices(
        type,
        NsdManager.PROTOCOL_DNS_SD,
        network,
        executor,
        discovery
      );
    } catch (RuntimeException error) {
      removeDiscovery(handle, discovery);
      listener.onLanStatus("LAN discovery unavailable");
      scheduleRetry(handle);
    }
  }

  private boolean removeDiscovery(
    long handle,
    NsdManager.DiscoveryListener discovery
  ) {
    Discovery current = discoveries.get(handle);
    if (current == null || current.listener != discovery) return false;
    discoveries.remove(handle);
    if (handle == -1) legacyStarted = false;
    return true;
  }

  private void scheduleRetry(long handle) {
    if (closed || discoveryRetries.containsKey(handle)) return;
    int failures = Math.min(5, discoveryFailures.getOrDefault(handle, 0) + 1);
    discoveryFailures.put(handle, failures);
    Runnable retry = () -> {
      discoveryRetries.remove(handle);
      if (!closed) onTopologyChanged(tracker.snapshot());
    };
    discoveryRetries.put(handle, retry);
    handler.postDelayed(retry, Math.min(8000, 500L << (failures - 1)));
  }

  private String key(NsdServiceInfo info, Network network) {
    return (
      info.getServiceName() +
      "@" +
      (network == null ? -1 : network.getNetworkHandle())
    );
  }

  private void found(NsdServiceInfo info, Network network) {
    if (closed) return;
    discoveredServices.put(key(info, network), info.getServiceName());
    if (NsdCompat.supportsServiceInfoCallbacks()) track(info, network);
    else resolve(info, network, 0);
  }

  private void track(NsdServiceInfo info, Network network) {
    String key = key(info, network);
    if (callbacks.containsKey(key)) return;
    NsdManager.ServiceInfoCallback callback =
      new NsdManager.ServiceInfoCallback() {
        @Override
        public void onServiceInfoCallbackRegistrationFailed(int error) {
          callbacks.remove(key);
          resolve(info, network, 0);
        }

        @Override
        public void onServiceInfoCallbackUnregistered() {
          callbacks.remove(key);
        }

        @Override
        public void onServiceLost() {
          callbacks.remove(key);
          discoveredServices.remove(key);
          listener.onServiceLost(
            info.getServiceName(),
            network == null ? -1 : network.getNetworkHandle()
          );
        }

        @Override
        public void onServiceUpdated(NsdServiceInfo updated) {
          if (!closed) listener.onEndpoint(LanEndpoint.from(updated, network));
        }
      };
    callbacks.put(key, callback);
    try {
      nsd.registerServiceInfoCallback(info, executor, callback);
    } catch (RuntimeException error) {
      callbacks.remove(key);
      resolve(info, network, 0);
    }
  }

  private void resolve(NsdServiceInfo info, Network network, int retry) {
    String key = key(info, network);
    if (closed || resolving.contains(key)) return;
    resolving.add(key);
    try {
      nsd.resolveService(
        info,
        new NsdManager.ResolveListener() {
          @Override
          public void onResolveFailed(NsdServiceInfo ignored, int error) {
            handler.post(() -> {
              resolving.remove(key);
              if (!closed && retry < 3) {
                legacyRetries.put(key, retry + 1);
                handler.postDelayed(
                  () -> resolve(info, network, retry + 1),
                  new long[] { 500, 1000, 2000 }[retry]
                );
              }
            });
          }

          @Override
          public void onServiceResolved(NsdServiceInfo resolved) {
            handler.post(() -> {
              resolving.remove(key);
              legacyRetries.remove(key);
              if (!closed) listener.onEndpoint(
                LanEndpoint.from(resolved, network)
              );
            });
          }
        }
      );
    } catch (RuntimeException error) {
      resolving.remove(key);
    }
  }

  private void lost(NsdServiceInfo info, Network network) {
    String key = key(info, network);
    discoveredServices.remove(key);
    NsdManager.ServiceInfoCallback callback = callbacks.remove(key);
    if (callback != null) try {
      nsd.unregisterServiceInfoCallback(callback);
    } catch (RuntimeException ignored) {}
    resolving.remove(key);
    legacyRetries.remove(key);
    listener.onServiceLost(
      info.getServiceName(),
      network == null ? -1 : network.getNetworkHandle()
    );
  }

  private void stopDiscovery(long handle) {
    Runnable retry = discoveryRetries.remove(handle);
    if (retry != null) handler.removeCallbacks(retry);
    discoveryFailures.remove(handle);
    Discovery discovery = discoveries.remove(handle);
    if (handle == -1) legacyStarted = false;
    if (discovery == null) return;
    try {
      nsd.stopServiceDiscovery(discovery.listener);
    } catch (RuntimeException ignored) {}
    long networkHandle =
      discovery.network == null ? -1 : discovery.network.getNetworkHandle();
    for (String key : new ArrayList<>(discoveredServices.keySet()))
      if (key.endsWith("@" + networkHandle)) {
        String serviceName = discoveredServices.remove(key);
        listener.onServiceLost(serviceName, networkHandle);
      }
    for (String key : new ArrayList<>(callbacks.keySet()))
      if (key.endsWith("@" + networkHandle)) {
        NsdManager.ServiceInfoCallback callback = callbacks.remove(key);
        try {
          nsd.unregisterServiceInfoCallback(callback);
        } catch (RuntimeException ignored) {}
      }
  }

  @Override
  public void close() {
    closed = true;
    tracker.removeListener(this);
    if (probeBrowser != null) probeBrowser.close();
    for (Runnable retry : discoveryRetries.values())
      handler.removeCallbacks(retry);
    discoveryRetries.clear();
    for (Long handle : new ArrayList<>(discoveries.keySet()))
      stopDiscovery(handle);
    for (NsdManager.ServiceInfoCallback callback : callbacks.values())
      try {
        nsd.unregisterServiceInfoCallback(callback);
      } catch (RuntimeException ignored) {}
    callbacks.clear();
    discoveredServices.clear();
    if (multicastLock != null && multicastLock.isHeld()) try {
      multicastLock.release();
    } catch (RuntimeException ignored) {}
  }
}
