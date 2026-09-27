package app.morphe.jam.companion;

import android.net.Network;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.*;

/** Immutable resolved NSD state. NsdServiceInfo itself is deliberately never retained. */
final class LanEndpoint {

  final String serviceName, serviceType, fingerprint;
  final Network network;
  final long networkHandle;
  final long discoveryHandle;
  final List<InetAddress> addresses;
  final int port;
  final Map<String, byte[]> attributes;
  final DiscoverySource source;

  String candidateKey() {
    byte[] jam = attributes.get("jam");
    return ConnectionCandidateManager.key(
      jam == null
        ? ""
        : new String(jam, java.nio.charset.StandardCharsets.UTF_8),
      networkHandle,
      addresses.get(0),
      port
    );
  }

  LanEndpoint single(Network route, InetAddress address) {
    return new LanEndpoint(
      serviceName,
      serviceType,
      route,
      discoveryHandle,
      Collections.singletonList(address),
      port,
      attributes,
      source
    );
  }

  LanEndpoint withSource(DiscoverySource source) {
    return new LanEndpoint(
      serviceName,
      serviceType,
      network,
      discoveryHandle,
      addresses,
      port,
      attributes,
      source
    );
  }

  LanEndpoint(
    String serviceName,
    String serviceType,
    Network network,
    List<InetAddress> addresses,
    int port,
    Map<String, byte[]> attributes
  ) {
    this(
      serviceName,
      serviceType,
      network,
      network == null ? -1 : network.getNetworkHandle(),
      addresses,
      port,
      attributes,
      DiscoverySource.NSD
    );
  }

  LanEndpoint(
    String serviceName,
    String serviceType,
    Network network,
    long discoveryHandle,
    List<InetAddress> addresses,
    int port,
    Map<String, byte[]> attributes
  ) {
    this(
      serviceName,
      serviceType,
      network,
      discoveryHandle,
      addresses,
      port,
      attributes,
      DiscoverySource.NSD
    );
  }

  private LanEndpoint(
    String serviceName,
    String serviceType,
    Network network,
    long discoveryHandle,
    List<InetAddress> addresses,
    int port,
    Map<String, byte[]> attributes,
    DiscoverySource source
  ) {
    this.source = source;
    this.serviceName = serviceName == null ? "" : serviceName;
    this.serviceType = serviceType == null ? "" : serviceType;
    this.network = network;
    this.networkHandle = network == null ? -1 : network.getNetworkHandle();
    this.discoveryHandle = discoveryHandle;
    this.addresses = Collections.unmodifiableList(
      new ArrayList<>(addresses == null ? Collections.emptyList() : addresses)
    );
    this.port = port;
    TreeMap<String, byte[]> copy = new TreeMap<>();
    if (attributes != null) for (Map.Entry<
      String,
      byte[]
    > entry : attributes.entrySet())
      copy.put(
        entry.getKey(),
        entry.getValue() == null ? new byte[0] : entry.getValue().clone()
      );
    this.attributes = Collections.unmodifiableMap(copy);
    this.fingerprint = fingerprint();
  }

  static LanEndpoint from(NsdServiceInfo info, Network knownNetwork) {
    Network network = knownNetwork;
    if (network == null && Build.VERSION.SDK_INT >= 33) network =
      info.getNetwork();
    List<InetAddress> addresses;
    if (Build.VERSION.SDK_INT >= 34) addresses = info.getHostAddresses();
    else addresses =
      info.getHost() == null
        ? Collections.emptyList()
        : Collections.singletonList(info.getHost());
    LanEndpoint endpoint = new LanEndpoint(
      info.getServiceName(),
      info.getServiceType(),
      network,
      knownNetwork == null ? -1 : knownNetwork.getNetworkHandle(),
      addresses,
      info.getPort(),
      info.getAttributes()
    );
    return endpoint.withSource(
      knownNetwork == null
        ? DiscoverySource.NSD
        : DiscoverySource.NSD_NETWORK_SCOPED
    );
  }

  private String fingerprint() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(
        digest,
        serviceName.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      );
      update(
        digest,
        serviceType.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      );
      update(
        digest,
        Long.toString(networkHandle).getBytes(
          java.nio.charset.StandardCharsets.UTF_8
        )
      );
      update(
        digest,
        Long.toString(discoveryHandle).getBytes(
          java.nio.charset.StandardCharsets.UTF_8
        )
      );
      List<byte[]> sortedAddresses = new ArrayList<>();
      for (InetAddress address : addresses)
        if (address != null) sortedAddresses.add(address.getAddress());
      sortedAddresses.sort(LanEndpoint::compareBytes);
      for (byte[] value : sortedAddresses) update(digest, value);
      update(
        digest,
        Integer.toString(port).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      );
      for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
        update(
          digest,
          entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        update(digest, entry.getValue());
      }
      return java.util.Base64.getEncoder()
        .withoutPadding()
        .encodeToString(digest.digest());
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static void update(MessageDigest digest, byte[] value) {
    digest.update((byte) 0);
    digest.update(value);
  }

  private static int compareBytes(byte[] a, byte[] b) {
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      int c = Byte.compare(a[i], b[i]);
      if (c != 0) return c;
    }
    return Integer.compare(a.length, b.length);
  }
}
