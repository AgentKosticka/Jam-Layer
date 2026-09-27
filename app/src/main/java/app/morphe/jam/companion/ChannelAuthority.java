package app.morphe.jam.companion;

/** Used under the host's queue lock. Epochs apply to independently authenticated channels only. */
final class ChannelAuthority<T> {

  private T primary;
  private long epoch;
  private final java.util.Set<T> retired = java.util.Collections.newSetFromMap(
    new java.util.WeakHashMap<>()
  );

  boolean promote(T authenticated, long nextEpoch) {
    if (
      authenticated == null ||
      nextEpoch <= epoch ||
      retired.contains(authenticated)
    ) return false;
    if (primary != null && primary != authenticated) retired.add(primary);
    primary = authenticated;
    epoch = nextEpoch;
    return true;
  }

  boolean permits(T channel) {
    return primary == channel;
  }
}
