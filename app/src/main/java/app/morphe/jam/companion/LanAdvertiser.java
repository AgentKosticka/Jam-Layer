package app.morphe.jam.companion;

import android.content.Context;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** One NSD registration per physical network when Android exposes that capability. */
final class LanAdvertiser
  implements AutoCloseable, LocalNetworkTracker.Listener
{

  interface Listener {
    void onLanStatus(String status);
  }

  private final NsdManager nsd;
  private final LocalNetworkTracker tracker;
  private final String type, name;
  private final int port;
  private final Map<String, byte[]> attributes;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<Long, NsdManager.RegistrationListener> registrations =
    new HashMap<>();
  private final Map<Long, Integer> registrationFailures = new HashMap<>();
  private final Map<Long, Runnable> registrationRetries = new HashMap<>();
  private AutoCloseable probeRegistration;
  private boolean legacyRegistered, closed;

  LanAdvertiser(
    Context context,
    LocalNetworkTracker tracker,
    String type,
    String name,
    int port,
    Map<String, byte[]> attributes,
    Listener listener
  ) {
    this.nsd = context
      .getApplicationContext()
      .getSystemService(NsdManager.class);
    this.tracker = tracker;
    this.type = type;
    this.name = name;
    this.port = port;
    this.attributes = new TreeMap<>(attributes);
    this.listener = listener;
  }

  void start() {
    if (closed || probeRegistration != null) return;
    probeRegistration = LanProbe.advertise(
      tracker,
      type,
      name,
      port,
      attributes
    );
    if (nsd == null) return;
    tracker.addListener(this);
  }

  @Override
  public void onTopologyChanged(LocalNetworkTracker.Snapshot topology) {
    handler.post(() -> {
      if (closed) return;
      // Publish on both the default route and physical local networks.
      if (!legacyRegistered) register(-1, null);
      if (!NsdCompat.supportsNetworkScopedDiscovery()) return;
      Set<Long> wanted = new HashSet<>();
      for (Network network : topology.localNetworks) {
        wanted.add(network.getNetworkHandle());
        if (!registrations.containsKey(network.getNetworkHandle())) register(
          network.getNetworkHandle(),
          network
        );
      }
      for (Long handle : new ArrayList<>(registrations.keySet()))
        if (handle != -1 && !wanted.contains(handle)) unregister(handle);
    });
  }

  private NsdServiceInfo info(Network network) {
    NsdServiceInfo value = new NsdServiceInfo();
    value.setServiceName(name);
    value.setServiceType(type);
    value.setPort(port);
    for (Map.Entry<String, byte[]> attribute : attributes.entrySet())
      value.setAttribute(
        attribute.getKey(),
        new String(
          attribute.getValue(),
          java.nio.charset.StandardCharsets.UTF_8
        )
      );
    if (network != null) value.setNetwork(network);
    return value;
  }

  private void register(long handle, Network network) {
    NsdManager.RegistrationListener registration =
      new NsdManager.RegistrationListener() {
        @Override
        public void onServiceRegistered(NsdServiceInfo ignored) {
          registrationFailures.remove(handle);
          listener.onLanStatus("LAN advertised");
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo ignored, int error) {
          if (removeIfCurrent(handle, this)) {
            listener.onLanStatus("LAN registration failed: " + error);
            scheduleRetry(handle);
          }
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo ignored) {}

        @Override
        public void onUnregistrationFailed(NsdServiceInfo ignored, int error) {
          if (removeIfCurrent(handle, this)) scheduleRetry(handle);
        }
      };
    try {
      registrations.put(handle, registration);
      if (handle == -1) legacyRegistered = true;
      nsd.registerService(
        info(network),
        NsdManager.PROTOCOL_DNS_SD,
        registration
      );
    } catch (RuntimeException error) {
      removeIfCurrent(handle, registration);
      listener.onLanStatus("LAN advertising unavailable");
      scheduleRetry(handle);
    }
  }

  private boolean removeIfCurrent(
    long handle,
    NsdManager.RegistrationListener registration
  ) {
    if (registrations.get(handle) != registration) return false;
    registrations.remove(handle);
    if (handle == -1) legacyRegistered = false;
    return true;
  }

  private void scheduleRetry(long handle) {
    if (closed || registrationRetries.containsKey(handle)) return;
    int failures = Math.min(
      5,
      registrationFailures.getOrDefault(handle, 0) + 1
    );
    registrationFailures.put(handle, failures);
    Runnable retry = () -> {
      registrationRetries.remove(handle);
      if (!closed) onTopologyChanged(tracker.snapshot());
    };
    registrationRetries.put(handle, retry);
    handler.postDelayed(retry, Math.min(8000, 500L << (failures - 1)));
  }

  private void unregister(long handle) {
    Runnable retry = registrationRetries.remove(handle);
    if (retry != null) handler.removeCallbacks(retry);
    registrationFailures.remove(handle);
    NsdManager.RegistrationListener registration = registrations.remove(handle);
    if (handle == -1) legacyRegistered = false;
    if (registration != null) try {
      nsd.unregisterService(registration);
    } catch (RuntimeException ignored) {}
  }

  @Override
  public void close() {
    closed = true;
    tracker.removeListener(this);
    if (probeRegistration != null) try {
      probeRegistration.close();
    } catch (Exception ignored) {}
    probeRegistration = null;
    for (Runnable retry : registrationRetries.values())
      handler.removeCallbacks(retry);
    registrationRetries.clear();
    for (Long handle : new ArrayList<>(registrations.keySet()))
      unregister(handle);
  }
}
