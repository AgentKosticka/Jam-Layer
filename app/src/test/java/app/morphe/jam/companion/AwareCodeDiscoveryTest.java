package app.morphe.jam.companion;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Test;

public class AwareCodeDiscoveryTest {
  @Test public void discoveryAcceptsOnlyVersionedPublicSessionIdentity() {
    String jam = UUID.randomUUID().toString();
    assertEquals(jam, AwareCodePairing.parseJam(("2:" + jam).getBytes(StandardCharsets.UTF_8)));
    assertNull(AwareCodePairing.parseJam(null));
    assertNull(AwareCodePairing.parseJam(("1:" + jam).getBytes(StandardCharsets.UTF_8)));
    assertNull(AwareCodePairing.parseJam(("code-hash:" + jam).getBytes(StandardCharsets.UTF_8)));
    assertNull(AwareCodePairing.parseJam("2:invalid".getBytes(StandardCharsets.UTF_8)));
  }

  @Test public void pathMetadataDependsOnlyOnPublicSession() throws Exception {
    String jam = UUID.randomUUID().toString();
    String passphrase = AwareCodePairing.dataPathPassphrase(jam);
    assertEquals(passphrase, AwareCodePairing.dataPathPassphrase(jam));
    assertNotEquals(passphrase, AwareCodePairing.dataPathPassphrase(UUID.randomUUID().toString()));
    assertTrue(passphrase.length() >= 8 && passphrase.length() <= 63);
  }
}
