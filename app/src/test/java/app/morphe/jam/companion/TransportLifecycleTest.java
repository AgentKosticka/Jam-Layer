package app.morphe.jam.companion;
import static org.junit.Assert.*;
import org.junit.Test;

public class TransportLifecycleTest {
  @Test public void backupDiscoveryDoesNotDowngradeConnectedSession() {
    TransportLifecycle l = new TransportLifecycle();
    l.provider(TransportLifecycle.Provider.LAN, TransportLifecycle.State.DISCOVERING);
    l.provider(TransportLifecycle.Provider.LAN, TransportLifecycle.State.AUTHENTICATING);
    assertEquals(TransportLifecycle.Session.AUTHENTICATING, l.session());
    l.connected();
    l.provider(TransportLifecycle.Provider.AWARE, TransportLifecycle.State.CONNECTING);
    assertEquals(TransportLifecycle.Session.CONNECTED, l.session());
    l.degraded(); l.handover(); l.connected();
    assertEquals(TransportLifecycle.Session.CONNECTED, l.session());
    l.close(); l.connected(); l.reconnect();
    assertFalse(l.provider(TransportLifecycle.Provider.LAN, TransportLifecycle.State.READY));
    assertEquals(TransportLifecycle.Session.ENDED, l.session());
  }
  @Test public void audioAndPermissionsWaitForExternalChange() {
    assertEquals(-1, TransportFailure.BLE_PAUSED_FOR_AUDIO.retryDelay(4));
    assertEquals(-1, TransportFailure.BLE_PERMISSION_MISSING.retryDelay(1));
    assertTrue(TransportFailure.AUTH_FAILED.retryDelay(1) > TransportFailure.AWARE_PATH_FAILED.retryDelay(1));
    assertEquals(TransportFailure.BLE_SCAN_FAILED, TransportFailure.ble("scan unavailable: 2"));
    assertEquals(TransportFailure.BLE_L2CAP_FAILED, TransportFailure.ble("L2CAP accept unavailable"));
  }
}
