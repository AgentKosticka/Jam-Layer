# Jam Layer transport architecture

Jam Layer never calls `bindProcessToNetwork`. LAN discovery is scoped to physical
Wi-Fi or Ethernet networks where Android permits it, and LAN sockets prefer that
explicit `Network` socket factory. System routing is a compatibility fallback, not
the first choice.

`NsdServiceInfo` is only an input to `LanEndpoint`, an immutable endpoint snapshot.
Its fingerprint includes network, port, addresses, and TXT attributes, so an NSD
update is connectable state rather than a permanently deduplicated service name.

Auto starts LAN and direct Wi-Fi Aware together. It never invokes Google Play
services or Samsung Sharing, so starting a Jam does not compete with the system
sharing service or require Bluetooth permissions.

LAN publishes and browses on both
the system default route and each visible physical Wi-Fi/Ethernet network.
Discovery and registration failures retry with bounded backoff. Short-code
lookup retries transient LAN connection failures while Aware lookup runs in
parallel. The code bootstrap uses an authenticated Aware data path because
Android permits a port-bearing Aware path only when it is secured; J-PAKE still
authenticates the short code before the invitation is released. A successful
code-pairing socket is then promoted directly into the Jam `SecureChannel`;
the already-established Aware data path stays open for that channel rather
than being torn down and rediscovered.

TCP arrival does not pick the session:
each candidate authenticates its existing `SecureChannel` outside the session lock,
and the first authenticated candidate commits. Other candidate sockets and
Aware requests are closed. A failure from one candidate leaves the other
transport running. Failed authentication can retry the affected endpoint.

When LAN wins, the participant keeps its Aware discovery session and known
peer handle warm. If the LAN socket fails, it asks that peer for an Aware path
before starting a new discovery session. A failed Aware socket deliberately
reattaches instead, because some devices invalidate peer state with the path.
A subscriber which remains active but finds no Aware peer is reattached after a
bounded 12 second window, covering vendor stacks that keep a stale discovery
session alive without delivering beacons.

An Aware data-path request is a resource. `AwareDataPath` owns one callback/request
and releases it on failure. Retries request fresh paths; they do not hammer a stale
Aware `Network`. A winning Aware candidate retains its request until its
socket/session is closed.

Invitation and SecureChannel authentication remain the ultimate peer authority. The
implementation does not identify or control any VPN product and never logs secrets,
pairing codes, or channel keys.

Direct Aware requires nearby hardware that supports Wi-Fi Aware. Devices on
unrelated networks beyond radio range need an internet relay, which this app
does not provide.
