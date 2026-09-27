package app.morphe.jam.companion;

import android.content.Context;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.net.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Independent public discovery providers feeding the same authenticated TCP candidates. */
final class LanDiscovery
  implements AutoCloseable, LocalNetworkTracker.Listener
{

  static final int PORT = 39548;
  private final Context context;
  private final LocalNetworkTracker tracker;
  private final String jam;
  private final int hostPort;
  private final boolean pairing;
  private final int discoveryPort;
  private final BooleanSupplier connected;
  private final Consumer<LanEndpoint> listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<Long, Worker> workers = new HashMap<>();
  private final WifiManager.MulticastLock multicastLock;
  private volatile boolean closed;
  private long replyWindow;
  private int replies;

  LanDiscovery(
    Context context,
    LocalNetworkTracker tracker,
    String jam,
    int hostPort,
    BooleanSupplier connected,
    Consumer<LanEndpoint> listener
  ) {
    this(context, tracker, jam, hostPort, connected, listener, false);
  }

  static LanDiscovery pairing(Context context, LocalNetworkTracker tracker,
    String jam, int hostPort, BooleanSupplier connected, Consumer<LanEndpoint> listener) {
    return new LanDiscovery(context, tracker,
      jam == null ? LanDiscoveryPacket.ANY_PAIRING_SESSION : jam,
      hostPort, connected, listener, true);
  }

  private LanDiscovery(Context context, LocalNetworkTracker tracker, String jam,
    int hostPort, BooleanSupplier connected, Consumer<LanEndpoint> listener, boolean pairing) {
    this.context = context;
    this.tracker = tracker;
    this.jam = jam;
    this.hostPort = hostPort;
    this.connected = connected;
    this.listener = listener;
    this.pairing = pairing;
    discoveryPort = pairing ? PORT + 1 : PORT;
    WifiManager wifi = context.getSystemService(WifiManager.class);
    multicastLock =
      wifi == null ? null : wifi.createMulticastLock("MorpheJam:discovery");
    if (multicastLock != null) multicastLock.setReferenceCounted(false);
  }

  void start() {
    handler.post(() -> {
    if (closed) return;
    if (multicastLock != null) try {
      multicastLock.acquire();
    } catch (RuntimeException ignored) {}
    tracker.addListener(this);
    });
  }

  public void onTopologyChanged(LocalNetworkTracker.Snapshot snapshot) {
    if (closed) return;
    Set<Long> current = new HashSet<>();
    for (Network network : snapshot.localNetworks) {
      LinkProperties properties = tracker
        .connectivity()
        .getLinkProperties(network);
      if (properties == null || properties.getInterfaceName() == null) continue;
      long key = network.getNetworkHandle();
      current.add(key);
      String signature =
        properties.getInterfaceName() +
        properties.getLinkAddresses().toString();
      Worker old = workers.get(key);
      if (
        old != null && old.signature.equals(signature) && old.thread.isAlive()
      ) continue;
      if (old != null) old.close();
      Worker worker = new Worker(network, properties, signature);
      workers.put(key, worker);
      worker.thread.start();
    }
    for (Long key : new ArrayList<>(workers.keySet()))
      if (!current.contains(key)) workers.remove(key).close();
  }

  private synchronized boolean mayReply() {
    long now = SystemClock.elapsedRealtime();
    if (now - replyWindow >= 1000) {
      replyWindow = now;
      replies = 0;
    }
    return replies++ < 32;
  }

  private final class Worker implements AutoCloseable, Runnable {

    final Network network;
    final LinkProperties properties;
    final String signature;
    final Thread thread;
    volatile MulticastSocket socket;
    volatile boolean stopped;

    Worker(Network network, LinkProperties properties, String signature) {
      this.network = network;
      this.properties = properties;
      this.signature = signature;
      thread = new Thread(this, "Jam local discovery");
      thread.setDaemon(true);
    }

    public void run() {
      try (MulticastSocket udp = new MulticastSocket(null)) {
        socket = udp;
        if (stopped || closed) return;
        udp.setReuseAddress(true);
        udp.bind(new InetSocketAddress(hostPort > 0 ? discoveryPort : 0));
        network.bindSocket(udp);
        udp.setBroadcast(true);
        udp.setSoTimeout(80);
        udp.setTimeToLive(1);
        NetworkInterface nic = NetworkInterface.getByName(
          properties.getInterfaceName()
        );
        if (nic == null) return;
        udp.setNetworkInterface(nic);
        InetAddress rawGroup = InetAddress.getByName(pairing ? "ff12::4d4a:5033" : "ff12::4d4a:5032");
        Inet6Address group = Inet6Address.getByAddress(
          null,
          rawGroup.getAddress(),
          nic.getIndex()
        );
        List<InetAddress> broadcasts = new ArrayList<>(),
          probes = new ArrayList<>();
        boolean ipv6 = false;
        for (LinkAddress link : properties.getLinkAddresses()) {
          if (
            link.getAddress() instanceof Inet4Address &&
            link.getPrefixLength() < 31
          ) {
            broadcasts.add(
              LanSubnet.broadcast(link.getAddress(), link.getPrefixLength())
            );
            for (InetAddress address : LanSubnet.probes(
              link.getAddress(),
              link.getPrefixLength()
            ))
              if (probes.size() < 254 && !probes.contains(address)) probes.add(
                address
              );
          }
          if (
            link.getAddress() instanceof Inet6Address &&
            link.getAddress().isLinkLocalAddress()
          ) ipv6 = true;
        }
        ipv6 &= !TransportOptions.disabled(context, "Ipv6Multicast");
        if (hostPort > 0 && ipv6) try {
          udp.joinGroup(new InetSocketAddress(group, discoveryPort), nic);
        } catch (Exception unsupported) {
          ipv6 = false;
        }
        long nonce = new java.security.SecureRandom().nextLong();
        long started = SystemClock.elapsedRealtime(),
          nextBurst = 0,
          nextCycle = started + TransportOptions.probeDelay(context);
        long broadcastNonce = nonce,
          multicastNonce = nonce + 1,
          activeNonce = nonce + 2;
        int burst = 0,
          probeIndex = probes.size();
        long nextProbeBatch = 0;
        while (!closed && !stopped) {
          udp.setSoTimeout(
            hostPort > 0 || connected.getAsBoolean() ? 1000 : 80
          );
          long now = SystemClock.elapsedRealtime();
          if (hostPort == 0 && !connected.getAsBoolean()) {
            if (now >= nextBurst) {
              if (
                !TransportOptions.disabled(context, "Ipv4Broadcast")
              ) for (InetAddress target : broadcasts)
                send(
                  udp,
                  new LanDiscoveryPacket(jam, broadcastNonce, 0).encode(),
                  target,
                  discoveryPort
                );
              if (ipv6) send(
                udp,
                new LanDiscoveryPacket(jam, multicastNonce, 0).encode(),
                group,
                discoveryPort
              );
              // Wi-Fi power saving can drop early multicast/broadcast bursts.
              // Half a packet per second after backoff avoids an eight-second
              // latency penalty without turning discovery into a scan flood.
              nextBurst = now + Math.min(2000, 500L << Math.min(2, burst++));
            }
            if (!TransportOptions.disabled(context, "ActiveProbe")) {
              if (now >= nextCycle) {
                probeIndex = 0;
                nextCycle = now + 15000;
              }
              if (now >= nextProbeBatch) {
                for (
                  int n = 0;
                  n < 16 && probeIndex < probes.size();
                  n++, probeIndex++
                ) send(
                  udp,
                  new LanDiscoveryPacket(jam, activeNonce, 0).encode(),
                  probes.get(probeIndex),
                  discoveryPort
                );
                nextProbeBatch = now + 100;
              }
            }
          }
          byte[] buffer = new byte[LanDiscoveryPacket.SIZE + 1];
          DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
          try {
            udp.receive(packet);
          } catch (SocketTimeoutException timeout) {
            continue;
          }
          LanDiscoveryPacket value = LanDiscoveryPacket.decode(
            buffer,
            packet.getOffset(),
            packet.getLength()
          );
          if (value == null) continue;
          if (hostPort > 0) {
            if ((!pairing || !connected.getAsBoolean()) && value.requests(jam, pairing) && mayReply()) send(
              udp,
              new LanDiscoveryPacket(jam, value.nonce, hostPort).encode(),
              packet.getAddress(),
              packet.getPort()
            );
          } else if (!connected.getAsBoolean() && value.port > 0) {
            String expectedJam = pairing ? value.jam : jam;
            if (pairing && !value.matchesPairing(broadcastNonce) &&
                !value.matchesPairing(multicastNonce) && !value.matchesPairing(activeNonce)) continue;
            DiscoverySource source = value.matches(expectedJam, broadcastNonce)
              ? DiscoverySource.IPV4_BROADCAST
              : value.matches(expectedJam, multicastNonce)
                ? DiscoverySource.IPV6_MULTICAST
                : value.matches(expectedJam, activeNonce)
                  ? DiscoverySource.ACTIVE_PROBE
                  : null;
            if (source == null) continue;
            probeIndex = probes.size();
            Map<String, byte[]> attrs = new HashMap<>();
            attrs.put(
              "jam",
              value.jam.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            attrs.put("v", new byte[] { '1' });
            LanEndpoint endpoint = new LanEndpoint(
              "udp",
              pairing ? "_morphepair._tcp." : "_morphejam._tcp.",
              network,
              Collections.singletonList(packet.getAddress()),
              value.port,
              attrs
            );
            final LanEndpoint discovered = endpoint.withSource(source);
            handler.post(() -> {
              if (!closed && !stopped) listener.accept(discovered);
            });
          }
        }
      } catch (Exception error) {
        if (!closed && !stopped) android.util.Log.w(
          "MorpheJam",
          "Local discovery unavailable: " + error.getClass().getSimpleName()
        );
      } finally {
        socket = null;
      }
    }

    private void send(
      DatagramSocket udp,
      byte[] bytes,
      InetAddress target,
      int port
    ) {
      try {
        udp.send(new DatagramPacket(bytes, bytes.length, target, port));
      } catch (Exception ignored) {
        /* One unavailable family must not stop the other providers. */
      }
    }

    public void close() {
      stopped = true;
      MulticastSocket udp = socket;
      if (udp != null) udp.close();
      thread.interrupt();
    }
  }

  public void close() {
    closed = true;
    handler.post(() -> {
    tracker.removeListener(this);
    for (Worker worker : workers.values()) worker.close();
    workers.clear();
    if (
      multicastLock != null && multicastLock.isHeld()
    ) multicastLock.release();
    });
  }
}
