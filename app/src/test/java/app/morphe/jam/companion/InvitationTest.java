package app.morphe.jam.companion;

import static org.junit.Assert.*;

import java.net.*;
import java.util.*;
import org.junit.Test;

public class InvitationTest {
  @org.junit.Test public void hintsRetainBothFamiliesAndStripSenderScope() throws Exception {
    Invitation invite = new Invitation();
    java.util.List<Invitation.Hint> hints = new java.util.ArrayList<>();
    for (int i = 1; i <= 5; i++) hints.add(new Invitation.Hint(java.net.InetAddress.getByName("10.0.0." + i), 1234));
    hints.add(new Invitation.Hint(java.net.Inet6Address.getByAddress(null,
      java.net.InetAddress.getByName("fe80::1234").getAddress(), 77), 1234));
    invite.setHints(hints);
    Invitation parsed = new Invitation(invite.uri());
    org.junit.Assert.assertEquals(4, parsed.hints.size());
    java.net.Inet6Address ipv6 = (java.net.Inet6Address) parsed.hints.get(1).address;
    org.junit.Assert.assertEquals(0, ipv6.getScopeId());
  }

  @Test
  public void v1AndV2RoundTripAndStaleHintsRemainAdvisory() throws Exception {
    Invitation invite = new Invitation();
    assertEquals(invite.jamId, new Invitation(invite.uri()).jamId);
    assertTrue(invite.uri().contains("v=1"));
    invite.setHints(
      Arrays.asList(
        new Invitation.Hint(InetAddress.getByName("10.0.0.254"), 1234)
      )
    );
    Invitation parsed = new Invitation(invite.uri());
    assertTrue(invite.uri().contains("v=2"));
    assertArrayEquals(invite.secret, parsed.secret);
    assertEquals("10.0.0.254", parsed.hints.get(0).address.getHostAddress());
    assertTrue(parsed.valid());
  }

  @Test
  public void deterministicBoundedHints() throws Exception {
    Invitation invite = new Invitation();
    List<Invitation.Hint> hints = new ArrayList<>();
    for (int i = 1; i < 10; i++) hints.add(
      new Invitation.Hint(InetAddress.getByName("10.0.0." + i), 80)
    );
    invite.setHints(hints);
    String first = invite.uri();
    Collections.reverse(hints);
    invite.setHints(hints);
    assertEquals(first, invite.uri());
    assertEquals(4, new Invitation(first).hints.size());
  }

  @Test
  public void rejectsInvalidAddressesPortsAndOversizedInput() throws Exception {
    for (String hint : new String[] {
      "AAAAAA.1",
      "fwAAAQ.1",
      "4AAAAQ.1",
      "CgAAAQ.0",
      "CgAAAQ.65536",
      "AQ.80",
    }) {
      try {
        Invitation.Hint.parse(hint);
        fail(hint);
      } catch (IllegalArgumentException expected) {}
    }
    try {
      new Invitation("x".repeat(1025));
      fail();
    } catch (IllegalArgumentException expected) {}
  }
}
