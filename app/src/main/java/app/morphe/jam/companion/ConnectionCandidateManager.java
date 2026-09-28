package app.morphe.jam.companion;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.*;

/** Session-only endpoint history. No discovery metadata or secrets enter identity. */
final class ConnectionCandidateManager {

  enum State {
    DISCOVERED,
    CONNECTING,
    AUTHENTICATING,
    READY,
    FAILED,
    CLOSED,
  }

  static String key(String jam, long network, InetAddress address, int port) {
    if (
      address == null || port < 1 || port > 65535
    ) throw new IllegalArgumentException("Invalid endpoint");
    return (
      jam +
      ":" +
      network +
      ":" +
      Base64.getEncoder().encodeToString(address.getAddress()) +
      ":" +
      (network < 0 && address instanceof Inet6Address
        ? ((Inet6Address) address).getScopeId()
        : 0) +
      ":" +
      port
    );
  }

  static final class Record {

    final String key;
    final long firstSeen;
    long lastSeen, started, authStarted, lastConnectDurationMs, lastAuthDurationMs, lastSuccessElapsed;
    int connectAttempts, authenticationAttempts, failures;
    long rttMs, lastDisconnectElapsed;
    int disconnects;
    String lastFailureClass = "";
    State state = State.DISCOVERED;
    final Set<DiscoverySource> sources = EnumSet.noneOf(DiscoverySource.class);

    Record(String key, long now) {
      this.key = key;
      firstSeen = lastSeen = now;
    }

    long score() {
      return (
        lastConnectDurationMs +
        lastAuthDurationMs +
        rttMs + Math.min(disconnects, 10) * 1500L +
        Math.min(failures, 10) * 1000L
      );
    }
  }

  private final Map<String, Record> records = new LinkedHashMap<>();

  synchronized Record observe(String key, DiscoverySource source, long now) {
    Record record = records.get(key);
    if (record == null) {
      if (records.size() >= 256) {
        Iterator<Record> it = records.values().iterator();
        while (it.hasNext()) {
          Record old = it.next();
          if (
            old.state == State.DISCOVERED ||
            old.state == State.FAILED ||
            old.state == State.CLOSED
          ) {
            it.remove();
            break;
          }
        }
        if (records.size() >= 256) return null;
      }
      record = new Record(key, now);
      records.put(key, record);
    }
    record.lastSeen = now;
    record.sources.add(source);
    return record;
  }

  synchronized boolean begin(Record r, long now) {
    if (
      r == null ||
      r.state == State.CONNECTING ||
      r.state == State.AUTHENTICATING ||
      r.state == State.READY
    ) return false;
    r.state = State.CONNECTING;
    r.started = now;
    r.connectAttempts++;
    return true;
  }

  synchronized void authenticating(Record r, long now) {
    if (r == null) return;
    r.lastConnectDurationMs = now - r.started;
    r.authStarted = now;
    r.authenticationAttempts++;
    r.state = State.AUTHENTICATING;
  }

  synchronized void success(Record r, long now) {
    if (r == null) return;
    if (r.state == State.AUTHENTICATING)
      r.lastAuthDurationMs = r.authStarted == 0 ? 0 : now - r.authStarted;
    r.lastSuccessElapsed = now;
    r.failures = 0;
    r.lastFailureClass = "";
    r.state = State.READY;
  }

  synchronized void failure(Record r, String failure) {
    if (r == null) return;
    r.lastFailureClass = failure;
    r.failures++;
    r.state = State.FAILED;
  }

  synchronized void release(Record r) {
    if (r != null) r.state = State.CLOSED;
  }

  synchronized long score(String key) {
    Record r = records.get(key);
    return r == null ? 1000 : r.score() + (r.lastSuccessElapsed == 0 ? 500 : 0);
  }

  synchronized long score(String key, long now) {
    Record r = records.get(key);
    return score(key) + (r == null || r.lastSuccessElapsed == 0 ? 0 :
      Math.min(1000, Math.max(0, now - r.lastSuccessElapsed) / 1000));
  }

  synchronized void rtt(String key, long millis, long now) {
    Record r = records.get(key);
    if (r == null) return;
    long sample = Math.max(0, Math.min(60000, millis));
    r.rttMs = r.rttMs == 0 ? sample : (r.rttMs * 3 + sample) / 4;
    if (r.disconnects > 0 && now - r.lastDisconnectElapsed >= 60000) {
      r.disconnects--;
      r.lastDisconnectElapsed = now;
    }
  }

  synchronized void disconnected(String key, long now) {
    Record r = records.get(key);
    if (r == null) return;
    r.disconnects = Math.min(10, r.disconnects + 1);
    r.lastDisconnectElapsed = now;
    r.lastFailureClass = "NETWORK_LOST";
  }
}
