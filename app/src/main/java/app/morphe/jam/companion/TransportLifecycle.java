package app.morphe.jam.companion;

import java.util.EnumMap;
import org.json.JSONObject;

/** Explicit session/provider transitions. CLOSED/ENDED reject delayed callbacks. */
final class TransportLifecycle {
  enum Provider { LAN, AWARE, BLE }
  enum State { IDLE, UNAVAILABLE, PAUSED, DISCOVERING, PEER_FOUND, CONNECTING,
    AUTHENTICATING, READY, FAILED_TEMPORARY, FAILED_PERMANENT, CLOSED }
  enum Session { IDLE, DISCOVERING, CONNECTING, AUTHENTICATING, CONNECTED,
    DEGRADED, HANDOVER, RECONNECTING, ENDED }
  private final EnumMap<Provider, State> providers = new EnumMap<>(Provider.class);
  private Session session = Session.IDLE;

  TransportLifecycle() { for (Provider p : Provider.values()) providers.put(p, State.IDLE); }
  synchronized boolean provider(Provider p, State next) {
    if (session == Session.ENDED || providers.get(p) == State.CLOSED) return false;
    if (providers.get(p) == next) return false;
    providers.put(p, next);
    if (session == Session.IDLE || session == Session.DISCOVERING ||
        session == Session.CONNECTING || session == Session.AUTHENTICATING) {
      if (next == State.AUTHENTICATING) session = Session.AUTHENTICATING;
      else if (next == State.CONNECTING && session != Session.AUTHENTICATING) session = Session.CONNECTING;
      else if (next == State.DISCOVERING && session == Session.IDLE) session = Session.DISCOVERING;
    }
    return true;
  }
  synchronized void connected() { if (session != Session.ENDED) session = Session.CONNECTED; }
  synchronized void degraded() { if (session == Session.CONNECTED) session = Session.DEGRADED; }
  synchronized void handover() { if (session != Session.ENDED) session = Session.HANDOVER; }
  synchronized void reconnect() { if (session != Session.ENDED) session = Session.RECONNECTING; }
  synchronized void close() {
    session = Session.ENDED;
    for (Provider p : Provider.values()) providers.put(p, State.CLOSED);
  }
  synchronized Session session() { return session; }
  synchronized JSONObject snapshot() {
    JSONObject value = new JSONObject();
    try {
      value.put("session", session.name());
      for (Provider p : Provider.values()) value.put(p.name(), providers.get(p).name());
    } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    return value;
  }
}
