package app.morphe.jam.companion;

import android.os.SystemClock;
import java.util.*;
import org.json.*;

/** Bounded, local, session-only event timeline. Never accepts payloads or credentials. */
final class TransportDiagnostics {

  private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
  private final long started = SystemClock.elapsedRealtime();

  synchronized void event(
    String event,
    LanEndpoint endpoint,
    long duration,
    String failure
  ) {
    try {
      JSONObject value = new JSONObject()
        .put("elapsedMs", SystemClock.elapsedRealtime() - started)
        .put("event", event)
        .put("durationMs", duration)
        .put("failureClass", failure);
      if (endpoint != null) value
        .put("source", endpoint.source.name())
        .put("networkHandle", endpoint.networkHandle)
        .put(
          "family",
          endpoint.addresses.get(0).getAddress().length == 4 ? 4 : 6
        );
      if (events.size() == 128) events.removeFirst();
      events.addLast(value);
      android.util.Log.i("MorpheJam", "Transport " + value);
    } catch (JSONException ignored) {}
  }

  synchronized JSONArray snapshot() {
    return new JSONArray(new ArrayList<>(events));
  }
}
