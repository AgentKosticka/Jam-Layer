package app.morphe.jam.companion;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Fixed-size discovery, containing public session identity and a correlation nonce only. */
final class LanDiscoveryPacket {

  static final int SIZE = 32,
    MAGIC = 0x4d4a5032;
  static final String ANY_PAIRING_SESSION = "00000000-0000-0000-0000-000000000000";
  final String jam;
  final long nonce;
  final int port;

  LanDiscoveryPacket(String jam, long nonce, int port) {
    if (
      !UUID.fromString(jam).toString().equals(jam) || port < 0 || port > 65535
    ) throw new IllegalArgumentException("Invalid discovery fields");
    this.jam = jam;
    this.nonce = nonce;
    this.port = port;
  }

  byte[] encode() {
    UUID id = UUID.fromString(jam);
    return ByteBuffer.allocate(SIZE)
      .putInt(MAGIC)
      .put((byte) 2)
      .put((byte) (port == 0 ? 1 : 2))
      .putLong(id.getMostSignificantBits())
      .putLong(id.getLeastSignificantBits())
      .putLong(nonce)
      .putShort((short) port)
      .array();
  }

  static LanDiscoveryPacket decode(byte[] bytes, int offset, int length) {
    if (length != SIZE) return null;
    try {
      ByteBuffer data = ByteBuffer.wrap(bytes, offset, length);
      if (data.getInt() != MAGIC || data.get() != 2) return null;
      int type = data.get();
      String jam = new UUID(data.getLong(), data.getLong()).toString();
      long nonce = data.getLong();
      int port = data.getShort() & 65535;
      if ((type != 1 && type != 2) || (type == 1) != (port == 0)) return null;
      return new LanDiscoveryPacket(jam, nonce, port);
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  boolean matches(String expectedJam, long expectedNonce) {
    return port > 0 && jam.equals(expectedJam) && nonce == expectedNonce;
  }

  boolean requests(String hostJam, boolean pairing) {
    return port == 0 && (jam.equals(hostJam) ||
      (pairing && ANY_PAIRING_SESSION.equals(jam)));
  }

  boolean matchesPairing(long expectedNonce) {
    return port > 0 && !ANY_PAIRING_SESSION.equals(jam) && nonce == expectedNonce;
  }
}
