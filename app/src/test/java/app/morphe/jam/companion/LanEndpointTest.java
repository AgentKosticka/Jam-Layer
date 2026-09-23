package app.morphe.jam.companion;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.util.*;
import org.junit.Test;

public class LanEndpointTest {

  private static LanEndpoint endpoint(String address, int port, String jam)
    throws Exception {
    Map<String, byte[]> attributes = new HashMap<>();
    attributes.put(
      "jam",
      jam.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    );
    attributes.put("v", "1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new LanEndpoint(
      "Jam-host",
      "_morphejam._tcp.",
      null,
      Collections.singletonList(InetAddress.getByName(address)),
      port,
      attributes
    );
  }

  @Test
  public void sameSnapshotHasTheSameFingerprint() throws Exception {
    assertEquals(
      endpoint("192.168.1.42", 1234, "jam-a").fingerprint,
      endpoint("192.168.1.42", 1234, "jam-a").fingerprint
    );
  }

  @Test
  public void addressPortAndTxtChangesProduceNewFingerprints()
    throws Exception {
    LanEndpoint baseline = endpoint("192.168.1.42", 1234, "jam-a");
    assertNotEquals(
      baseline.fingerprint,
      endpoint("192.168.1.73", 1234, "jam-a").fingerprint
    );
    assertNotEquals(
      baseline.fingerprint,
      endpoint("192.168.1.42", 4321, "jam-a").fingerprint
    );
    assertNotEquals(
      baseline.fingerprint,
      endpoint("192.168.1.42", 1234, "jam-b").fingerprint
    );
  }

  @Test
  public void separateDiscoveryScopesHaveSeparateFingerprints()
    throws Exception {
    LanEndpoint global = endpoint("192.168.1.42", 1234, "jam-a");
    LanEndpoint scoped = new LanEndpoint(
      global.serviceName,
      global.serviceType,
      null,
      42,
      global.addresses,
      global.port,
      global.attributes
    );
    assertNotEquals(global.fingerprint, scoped.fingerprint);
    assertEquals(-1, global.discoveryHandle);
    assertEquals(42, scoped.discoveryHandle);
  }
}
