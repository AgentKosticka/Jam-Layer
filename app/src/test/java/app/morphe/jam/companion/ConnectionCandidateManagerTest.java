package app.morphe.jam.companion;

import static org.junit.Assert.*;

import java.net.*;
import org.junit.Test;

public class ConnectionCandidateManagerTest {

  @Test
  public void provenanceSharesOneReservationThroughAuthentication()
    throws Exception {
    ConnectionCandidateManager manager = new ConnectionCandidateManager();
    String key = ConnectionCandidateManager.key(
      "jam",
      12,
      InetAddress.getByName("10.0.0.2"),
      1234
    );
    ConnectionCandidateManager.Record record = manager.observe(
      key,
      DiscoverySource.NSD,
      10
    );
    assertTrue(manager.begin(record, 15));
    assertSame(
      record,
      manager.observe(key, DiscoverySource.IPV4_BROADCAST, 20)
    );
    assertFalse(manager.begin(record, 20));
    manager.authenticating(record, 25);
    assertFalse(
      manager.begin(manager.observe(key, DiscoverySource.INVITE_HINT, 30), 30)
    );
    manager.success(record, 40);
    assertFalse(manager.begin(record, 45));
    assertEquals(3, record.sources.size());
    assertEquals(10, record.lastConnectDurationMs);
    assertEquals(15, record.lastAuthDurationMs);
    manager.release(record);
    assertTrue(manager.begin(record, 50));
    assertEquals(2, record.connectAttempts);
  }

  @Test
  public void identitySeparatesNetworksAddressesPortsAndSessions()
    throws Exception {
    InetAddress a = InetAddress.getByName("10.0.0.2"),
      b = InetAddress.getByName("10.0.0.3");
    String key = ConnectionCandidateManager.key("a", 1, a, 12);
    assertNotEquals(key, ConnectionCandidateManager.key("a", 2, a, 12));
    assertNotEquals(key, ConnectionCandidateManager.key("b", 1, a, 12));
    assertNotEquals(key, ConnectionCandidateManager.key("a", 1, b, 12));
    assertNotEquals(key, ConnectionCandidateManager.key("a", 1, a, 13));
  }

  @Test
  public void failuresPenalizeOnlyAffectedEndpointAndSuccessResetsPenalty() {
    ConnectionCandidateManager manager = new ConnectionCandidateManager();
    ConnectionCandidateManager.Record a = manager.observe(
      "a",
      DiscoverySource.NSD,
      0
    );
    ConnectionCandidateManager.Record b = manager.observe(
      "b",
      DiscoverySource.NSD,
      0
    );
    manager.begin(a, 1);
    manager.failure(a, "AUTH_FAILED");
    assertTrue(a.score() > b.score());
    assertTrue(manager.begin(a, 2));
    manager.authenticating(a, 3);
    manager.success(a, 4);
    assertEquals(0, a.failures);
    assertEquals("", a.lastFailureClass);
  }
}
