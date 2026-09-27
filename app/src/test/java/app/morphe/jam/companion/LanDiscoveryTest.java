package app.morphe.jam.companion;

import static org.junit.Assert.*;

import java.net.*;
import java.util.*;
import org.junit.Test;

public class LanDiscoveryTest {

  @Test
  public void directedBroadcastUsesActualPrefix() throws Exception {
    InetAddress local = InetAddress.getByName("10.20.4.17");
    assertEquals(
      "10.20.4.255",
      LanSubnet.broadcast(local, 24).getHostAddress()
    );
    assertEquals(
      "10.20.5.255",
      LanSubnet.broadcast(local, 23).getHostAddress()
    );
    assertEquals(
      "10.20.255.255",
      LanSubnet.broadcast(local, 16).getHostAddress()
    );
    assertEquals("10.20.4.19", LanSubnet.broadcast(local, 30).getHostAddress());
    assertTrue(LanSubnet.probes(local, 16).size() <= 254);
    assertFalse(LanSubnet.probes(local, 24).contains(local));
    assertEquals(1, LanSubnet.probes(local, 30).size());
    assertTrue(LanSubnet.probes(local, 32).isEmpty());
  }

  @Test
  public void packetValidationAndNonceCorrelation() {
    String jam = UUID.randomUUID().toString();
    byte[] request = new LanDiscoveryPacket(jam, 123, 0).encode();
    assertEquals(32, request.length);
    assertEquals(0, LanDiscoveryPacket.decode(request, 0, 32).port);
    byte[] reply = new LanDiscoveryPacket(jam, 123, 65535).encode();
    LanDiscoveryPacket offer = LanDiscoveryPacket.decode(reply, 0, 32);
    assertTrue(offer.matches(jam, 123));
    assertFalse(offer.matches(jam, 124));
    assertFalse(offer.matches(UUID.randomUUID().toString(), 123));
    assertNull(LanDiscoveryPacket.decode(reply, 0, 31));
    assertNull(LanDiscoveryPacket.decode(Arrays.copyOf(reply, 33), 0, 33));
    reply[0] = 0;
    assertNull(LanDiscoveryPacket.decode(reply, 0, 32));
    request[4] = 3;
    assertNull(LanDiscoveryPacket.decode(request, 0, 32));
    request = new LanDiscoveryPacket(jam, 123, 0).encode();
    request[5] = 2;
    assertNull(LanDiscoveryPacket.decode(request, 0, 32));
  }

  @Test(expected = IllegalArgumentException.class)
  public void invalidUuidRejected() {
    new LanDiscoveryPacket("invalid", 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void invalidPortRejected() {
    new LanDiscoveryPacket(UUID.randomUUID().toString(), 1, 65536);
  }
}
