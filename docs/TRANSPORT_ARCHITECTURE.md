# Jam Layer local transport architecture

Jam traffic stays local. The app never calls `bindProcessToNetwork`, changes the default route, enables a hotspot, disconnects infrastructure Wi-Fi, or uses Google Nearby Connections. TCP and UDP sockets use physical Wi-Fi/Ethernet `Network`s. The existing VPN system-route fallback remains in `LanConnection`.

## Discovery and candidates

Discovery produces advisory endpoints; it does not authenticate a host. `LanEndpoint` snapshots metadata and provenance. `Nearby` expands addresses onto physical networks. `ConnectionCandidateManager` keys attempts by Jam ID, network handle, address bytes and port, independently of service name, TXT fields or discovery source. Reservations last through SecureChannel authentication. Distinct addresses race with a 100 ms stagger inside address lists and at most eight outstanding LAN candidates. First authenticated acceptable candidate wins. Attempt owners close losing sockets even during blocked connects.

Session-only history records provenance, timestamps, attempts, durations, failures and success. Reconnect ordering includes connect/authentication duration, smoothed PING RTT, consecutive failures, recent disconnect penalties and age since success. Disconnect penalties survive a successful reconnect and decay during stable measurements. Primary and backup PING samples run every 30 seconds; queue command duration is not treated as network RTT. Aware/BLE also retain session-local transport history. Lost network handles are invalidated. No credentials enter candidate history.

LAN discovery layers:

* Invitations parse v1 and v2. Hosts include up to four deterministic private/link-local IPv4 and local IPv6 hints in v2, alternating families so IPv6 is not crowded out. No Android network handle or sender interface index is serialized. Guests scope link-local IPv6 hints to each receiving physical interface. Invitations without hints stay v1. Old v1-only apps cannot parse a v2 QR; those clients need a v1 invitation.
* Android NSD runs on default and physical routes. Browser and advertiser state is confined to the main handler, including legacy callbacks and cleanup.
* Existing MJP1 gateway discovery remains on UDP 39547 for hotspot and short-code compatibility.
* `LanDiscovery` uses UDP 39548, one socket per physical network. IPv4 directed broadcast uses actual prefix lengths. IPv6 uses scoped `ff12::4d4a:5032`, hop limit one. Replies are unicast.
* Active discovery starts after two seconds without a usable LAN path. At most 254 addresses per network/cycle, batches of 16 at least 100 ms apart, 15-second cycle cooldown. Larger subnets use the local /24 window; /31 and /32 are skipped. Probing stops on an offer or authenticated LAN readiness. Auto may continue LAN discovery while Aware is primary to obtain a backup.

MJP2 packets are exactly 32 bytes: magic, version, request/offer kind, UUID, random 64-bit correlation nonce, port. Parsers reject incorrect lengths, versions, kinds and request/offer port combinations. Providers use distinct nonces. Host replies are capped at 32/second across discovery sockets. Packets contain no invitation secrets, pairing codes, verifiers or channel keys.

Short-code pairing uses the same broadcast, IPv6 and bounded active-probe implementation on UDP 39549 and multicast group `ff12::4d4a:5033`, in parallel with existing NSD/gateway discovery. A wildcard public-session request is accepted only by the pairing responder; replies identify the public Jam ID and pairing port. Code verification remains J-PAKE followed by SecureChannel. Address/network candidates are deduplicated and staggered; losing TCP attempts close even during connect. The winning pairing socket transfers into the Jam session without another discovery round. Its pairing port is not cached as a normal Jam reconnect endpoint.

Aware code discovery uses `morphepair-v2` and advertises only a version and public Jam ID. Its Android data-path passphrase is derived only from that public ID, and provides no peer authentication; J-PAKE and SecureChannel remain mandatory. The previous code-derived advertisement and data-path passphrase have been removed to prevent offline code checking. Both peers need this update for Aware short-code discovery; LAN/BLE pairing and QR invitation formats are unchanged.

## Authenticated backup and handover

Auto retains one independently authenticated backup on the other LAN/Aware transport. A healthy LAN/Aware primary is not switched merely because the other appeared. BLE still upgrades to LAN/Aware. Backups carry PING only, every 30 seconds; host read budget is 45 seconds.

An optional version-1 `CHANNEL` operation runs inside SecureChannel; its authentication/encryption wire format is unchanged. Participants negotiate BACKUP, then promote PRIMARY with increasing epochs. Older hosts can reject the extension and serve primary-only sessions. Host authority is keyed by authenticated client ID. Backup creation counts as the same participant. Queue commands execute under the canonical host queue lock and require current-primary authority after role negotiation. Retired channels cannot reclaim authority even with a higher epoch. Legacy clients remain compatible until role negotiation.

On primary failure the guest promotes its backup, commits under the lifecycle lock, updates route/transport, and resynchronizes state. Failed edits are not blindly replayed: existing unknown-outcome semantics remain. Discovery rebuilds the backup. If backup fails, cached endpoints and discovery provide cold recovery. Authentication and generation/session checks remain mandatory.

## Topology and Bluetooth

`LocalNetworkTracker` compares physical networks, link properties, default network and VPN presence before publishing. A meaningful change refreshes retry budgets and prepares an independently authenticated replacement while keeping the primary alive. Auto and forced LAN can prepare another LAN endpoint, including a different family/interface, without opening a duplicate of the current endpoint. The replacement stays non-authoritative until primary failure; promotion uses the existing encrypted role/epoch exchange and state resync. An existing backup is retained. New networks get workers; removed networks cancel pending attempts. Broad VPN/AP transitions still require physical acceptance testing.

Existing BLE policy remains conservative: any connected Bluetooth audio output pauses scanning, advertising and L2CAP. Auto retains its existing 1.5-second Wi-Fi head start and low-power fallback. No more aggressive radio policy was introduced without audio-hardware testing.

## Diagnostics and testing

`TransportDiagnostics` keeps a 128-event session timeline and structured local logcat with elapsed time, source, address family, network handle, duration and failure category. It never accepts invitations/payloads. `STATE` adds `backupTransport` and logical peer count.

The separate instrumentation APK extends `LayerScenario` with provider isolation, expected authenticated source, forced primary close, recovery timing and backup validation. `TransportOptions` overrides are process-local and available only in debuggable builds. Legacy persistent preferences are ignored, so a test cannot leave Aware/BLE disabled in a later normal app session. The fixture explicitly checks that regression. `scripts/test-local-transport.ps1` saves results and both-device logs under `build/transport-results`. The fixture validates encrypted commands, duplicates, canonical state and cleanup; it is not a real YTM playback/native queue test.

`TransportLifecycle` exposes explicit provider and session states through `STATE.transportLifecycle`. Backup discovery cannot downgrade a connected session; terminal state rejects late callbacks. Candidate/resource ownership remains in each transport. Shared failure categories and bounded retry delays cover LAN, Aware availability/attach/discovery/path/socket failures and BLE permissions/audio/scan/advertise/L2CAP failures. Audio and permission blocks wait for external changes. Aware path retries renew the peer exchange and reattach guest discovery after repeated failures, without tearing down a healthy Aware primary. Pending Aware socket connects are cancellable, and missing peer information has a deadline.

Physical VPN/AP roaming, many-client capacity, mixed-version and Bluetooth playback acceptance remain separate from the targeted device checks. The replacement test replays a topology notification over real interfaces; it is not an AP roaming test. Score improvements affect reconnect ordering and diagnostics; a healthy primary is retained rather than continuously switched based on small score differences.
