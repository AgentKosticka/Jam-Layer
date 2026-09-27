package app.morphe.jam.companion;

import java.net.*;
import java.util.*;

final class LanSubnet {

  static long number(InetAddress address) {
    byte[] b = address.getAddress();
    if (b.length != 4) throw new IllegalArgumentException("IPv4 required");
    return (
      ((b[0] & 255L) << 24) |
      ((b[1] & 255L) << 16) |
      ((b[2] & 255L) << 8) |
      (b[3] & 255L)
    );
  }

  static InetAddress address(long n) {
    try {
      return InetAddress.getByAddress(new byte[] {
        (byte) (n >> 24),
        (byte) (n >> 16),
        (byte) (n >> 8),
        (byte) n,
      });
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static long mask(int prefix) {
    if (prefix < 0 || prefix > 32) throw new IllegalArgumentException(
      "Invalid prefix"
    );
    return prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
  }

  static InetAddress broadcast(InetAddress local, int prefix) {
    return address(
      (number(local) & mask(prefix)) | (~mask(prefix) & 0xffffffffL)
    );
  }

  static List<InetAddress> probes(InetAddress local, int prefix) {
    // Larger networks are deliberately bounded to the local /24 window.
    if (prefix >= 31) return Collections.emptyList();
    long n = number(local),
      mask = mask(Math.max(24, prefix)),
      base = n & mask;
    long end = base | (~mask & 0xffffffffL);
    List<InetAddress> values = new ArrayList<>();
    for (long i = base + 1; i < end && values.size() < 254; i++) if (
      i != n
    ) values.add(address(i));
    return values;
  }
}
