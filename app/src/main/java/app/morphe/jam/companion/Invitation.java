package app.morphe.jam.companion;

import java.net.InetAddress;
import java.net.URI;
import java.util.*;

public final class Invitation {

  public final String jamId;
  public final byte[] secret;
  public final long expires;
  static final int MAX_HINTS = 4;
  static final int MAX_LENGTH = 1024;
  volatile List<Hint> hints = Collections.emptyList();

  static final class Hint {

    final InetAddress address;
    final int port;

    Hint(InetAddress address, int port) {
      if (
        address == null ||
        address.isAnyLocalAddress() ||
        address.isLoopbackAddress() ||
        address.isMulticastAddress() ||
        port < 1 ||
        port > 65535
      ) throw new IllegalArgumentException("Invalid endpoint hint");
      boolean ula =
        address.getAddress().length == 16 &&
        (address.getAddress()[0] & 0xfe) == 0xfc;
      if (
        !address.isSiteLocalAddress() && !address.isLinkLocalAddress() && !ula
      ) throw new IllegalArgumentException("Endpoint hint must be local");
      this.address = address;
      this.port = port;
    }

    String encode() {
      return SecureChannel.encode(address.getAddress()) + "." + port;
    }

    static Hint parse(String text) {
      try {
        String[] fields = text.split("\\.", -1);
        if (fields.length != 2) throw new IllegalArgumentException(
          "Invalid endpoint hint"
        );
        byte[] bytes = SecureChannel.decode(fields[0]);
        if (
          bytes.length != 4 && bytes.length != 16
        ) throw new IllegalArgumentException("Invalid address family");
        return new Hint(
          InetAddress.getByAddress(bytes),
          Integer.parseInt(fields[1])
        );
      } catch (java.net.UnknownHostException error) {
        throw new IllegalArgumentException(error);
      }
    }
  }

  void setHints(List<Hint> values) {
    TreeMap<String, Hint> unique = new TreeMap<>();
    for (Hint hint : values) unique.put(hint.encode(), hint);
    ArrayList<Hint> bounded = new ArrayList<>(unique.values());
    // Alternate families so several IPv4 interfaces cannot crowd IPv6 out of the QR.
    List<Hint> v4 = new ArrayList<>(), v6 = new ArrayList<>();
    for (Hint hint : bounded) (hint.address.getAddress().length == 4 ? v4 : v6).add(hint);
    bounded.clear();
    for (int i = 0; i < Math.max(v4.size(), v6.size()); i++) {
      if (i < v4.size()) bounded.add(v4.get(i));
      if (i < v6.size()) bounded.add(v6.get(i));
    }
    hints = Collections.unmodifiableList(
      bounded.subList(0, Math.min(MAX_HINTS, bounded.size()))
    );
  }

  public Invitation() {
    jamId = UUID.randomUUID().toString();
    secret = SecureChannel.random(32);
    expires = System.currentTimeMillis() + 12 * 60 * 60 * 1000L;
  }

  public Invitation(String value) {
    if (
      value == null || value.length() > MAX_LENGTH
    ) throw new IllegalArgumentException("Invalid invite");
    URI uri = URI.create(value.trim());
    if (
      !"morphejam".equals(uri.getScheme()) || !"join".equals(uri.getHost())
    ) throw new IllegalArgumentException("Not a Jam invite");
    Map<String, String> p = new HashMap<>();
    for (String field : Objects.requireNonNull(uri.getRawQuery()).split("&")) {
      String[] pair = field.split("=", 2);
      if (
        pair.length != 2 || p.put(pair[0], pair[1]) != null
      ) throw new IllegalArgumentException("Invalid invite fields");
    }
    if (
      !"1".equals(p.get("v")) && !"2".equals(p.get("v"))
    ) throw new IllegalArgumentException("Unsupported protocol");
    jamId = UUID.fromString(p.get("jam")).toString();
    secret = SecureChannel.decode(p.get("secret"));
    expires = Long.parseLong(p.get("exp"));
    if (
      secret.length != 32 ||
      expires <= System.currentTimeMillis() ||
      expires > System.currentTimeMillis() + 13 * 60 * 60 * 1000L
    ) throw new IllegalArgumentException("Expired or invalid invite");
    if ("2".equals(p.get("v")) && p.containsKey("ep")) {
      String[] encoded = p.get("ep").split(",", -1);
      if (encoded.length > MAX_HINTS) throw new IllegalArgumentException(
        "Too many endpoint hints"
      );
      List<Hint> decoded = new ArrayList<>();
      for (String hint : encoded) decoded.add(Hint.parse(hint));
      setHints(decoded);
    }
  }

  public boolean valid() {
    return expires > System.currentTimeMillis();
  }

  public String uri() {
    List<Hint> current = hints;
    String base =
      "morphejam://join?v=" +
      (current.isEmpty() ? "1" : "2") +
      "&jam=" +
      jamId +
      "&secret=" +
      SecureChannel.encode(secret) +
      "&exp=" +
      expires;
    if (current.isEmpty()) return base;
    StringJoiner encoded = new StringJoiner(",");
    for (Hint hint : current) encoded.add(hint.encode());
    return base + "&ep=" + encoded;
  }

  public void destroy() {
    Arrays.fill(secret, (byte) 0);
  }
}
