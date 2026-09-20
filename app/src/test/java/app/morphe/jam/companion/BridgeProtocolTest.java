package app.morphe.jam.companion;

import app.morphe.jam.ipc.BridgeProtocol;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeProtocolTest {
    @Test public void originalBridgeRemainsCompatible() throws Exception {
        JSONObject legacy = new JSONObject().put("op", "SNAPSHOT");
        assertSame(legacy, BridgeProtocol.validate(legacy));
    }

    @Test public void advertisementPreservesOperationAndRevision() throws Exception {
        JSONObject request = new JSONObject().put("op", "MOVE").put("revision", "host:42");
        JSONObject received = new JSONObject(BridgeProtocol.advertise(request).toString());
        assertEquals("MOVE", BridgeProtocol.validate(received).getString("op"));
        assertEquals("host:42", received.getString("revision"));
    }

    @Test public void newerPeerCanExplicitlySupportOurVersion() throws Exception {
        JSONObject message = BridgeProtocol.advertise(new JSONObject());
        message.getJSONObject("bridgeProtocol").put("version", 2);
        assertSame(message, BridgeProtocol.validate(message));
    }

    @Test public void incompatibleAndMalformedVersionsAreRejected() throws Exception {
        for (Object value : new Object[]{0, -1, "1", 1.5, JSONObject.NULL}) {
            JSONObject message = BridgeProtocol.advertise(new JSONObject());
            message.getJSONObject("bridgeProtocol").put("version", value);
            rejects(message);
        }
        JSONObject future = BridgeProtocol.advertise(new JSONObject());
        future.getJSONObject("bridgeProtocol").put("version", 2).put("minimumVersion", 2);
        rejects(future);
        rejects(new JSONObject().put("bridgeProtocol", JSONObject.NULL));
        rejects(new JSONObject().put("bridgeProtocol", new JSONObject()));
    }

    @Test public void missingOrUnsupportedRequiredCapabilitiesAreRejected() throws Exception {
        JSONObject missing = BridgeProtocol.advertise(new JSONObject());
        missing.getJSONObject("bridgeProtocol").put("features", new JSONArray());
        rejects(missing);
        JSONObject unsupported = BridgeProtocol.advertise(new JSONObject());
        unsupported.getJSONObject("bridgeProtocol").put("requires", new JSONArray().put("future-feature"));
        rejects(unsupported);
    }

    @Test public void optionalFutureFeaturesAreHarmless() throws Exception {
        JSONObject message = BridgeProtocol.advertise(new JSONObject());
        message.getJSONObject("bridgeProtocol").getJSONArray("features").put("future-feature");
        assertSame(message, BridgeProtocol.validate(message));
    }

    private static void rejects(JSONObject message) {
        try {
            BridgeProtocol.validate(message);
            fail("Accepted incompatible bridge: " + message);
        } catch (IllegalArgumentException | org.json.JSONException expected) { }
    }
}
