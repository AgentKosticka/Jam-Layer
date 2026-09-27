# Transport acceptance matrix

Run each case on two physical devices without restarting either app between
iterations.

| Host | Guest | Mode | Expected |
| --- | --- | --- | --- |
| No VPN | No VPN | LAN | Direct LAN connection |
| No VPN | No VPN | Aware | Aware connection |
| No VPN | No VPN | Auto | First authenticated transport wins |
| Partial-tunnel VPN | Partial-tunnel VPN | LAN/Auto | Normal LAN connection or documented system-route fallback |
| Exit node with LAN allowed | Same | LAN | Connects |
| Exit node with LAN disallowed | Same | LAN | Actionable LAN/VPN failure |
| VPN changes during discovery | Any | Auto | Discovery recovers without an Aware restart caused only by VPN state |
| Wi-Fi changes while VPN remains | Any | Auto | LAN rediscovery/reconnect |
| Aware unavailable | Any | Auto | LAN wins |
| LAN unavailable | Any | Auto | Aware wins |
| Aware discovery stalls | No VPN | Auto | Subscriber reattaches, then another transport may win |
| Short code with VPN | Same | Auto | Pairing succeeds |

Release gate: 20 consecutive Auto sessions, 20 forced-LAN sessions, 10 forced-Aware
sessions, 5 VPN-toggle reconnect cycles, and 5 short-code pairing cycles. Validate
queue convergence and authenticated channel replacement during reconnects.

2026-09-23 device smoke check: A069P (Android 37) hosted and SM-X620 (Android
36) joined. Forced LAN and forced Aware each authenticated, loaded the same
25-item native queue, and recovered an intentionally closed secure channel
without changing the queue. Auto joined an Aware-only host and recovered the
same way. Both devices were on the same Wi-Fi for these checks; the
different-LAN case remains for manual acceptance testing.

2026-09-23 direct-Aware check: with LAN excluded and the devices on distinct
networks, the same phone and tablet discovered and authenticated directly over
Wi-Fi Aware, loaded the 25-item host queue, then recovered an intentionally
closed channel without queue changes.

2026-09-23 cold short-code check: A069P (Android 37) and SM-X620 (Android 36)
were on separate network configurations. The guest discovered the host code,
completed its authenticated Wi-Fi Aware code exchange, then discovered and
connected to the Jam over `AWARE_NETWORK`. It loaded the 25-item host queue and
recovered an intentionally closed secure channel without queue changes.

2026-09-23 code-handoff smoke check: the updated Companion was installed on
both devices. The SM-X620 promoted its short-code Aware pairing socket directly
to an authenticated Jam channel on `AWARE_NETWORK` 282 ms after that socket
became available. Both devices passed bridge capability smoke checks. The
queue-edit portion was not run because the host's active queue contained only
two items; its test fixture requires at least three.

## 2026-09-27 local-transport implementation and device validation

Devices: A069P (`003203627002399`, Android 37) and SM-X620 (`R52YA0E5KEZ`, Android 36), on `10.0.0.0/24`. Both host/guest orientations were exercised. Bluetooth remained enabled without attached audio hardware; phone mobile data remained enabled. Wi-Fi associations and addresses stayed unchanged, and both devices answered an internet ping during testing. This is not a YouTube Music internet/playback continuity measurement.

The current build implements endpoint normalization/history, direct v2 invitation hints (v1 parsing retained), directed broadcast, scoped IPv6 multicast, delayed bounded UDP probing, staggered/cancellable TCP candidates, cached reconnect, meaningful topology refreshes, authenticated LAN/Aware backup and promotion, encrypted channel roles/epochs, logical participant counting, and local diagnostics. Existing BLE audio-first timing/policy is retained.

The first baseline LAN join authenticated but failed the forced-reconnect assertion after 45 seconds: cached endpoints were not retried by `Nearby.resume()`. Baseline Aware samples joined in 4920, 3047 and 9721 ms and reconnected; Auto samples joined in 2654 and 4092 ms and reconnected. Baseline sample sizes are too small for performance claims. Candidate normalization plus cached retries repaired LAN recovery before new discovery providers were added.

A later Auto repetition found an NSD cleanup crash (`HashMap.keysToArray` in `LanBrowser.stopDiscovery`). Legacy NSD callbacks were mutating maps from the NSD thread. Browser/advertiser callbacks, startup and cleanup now use the main handler, with callback identity checks. The failed run remains in `build/transport-results/gate-auto`, separate from the successful rerun under `final-auto`. Early broadcast-only retries occasionally took 24 seconds; discovery retry backoff was subsequently capped at two seconds rather than eight.

Run from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
.\scripts\test-local-transport.ps1 -Mode LAN -Only Ipv4Broadcast -Runs 5 -Label broadcast
.\scripts\test-local-transport.ps1 -Mode Auto -WarmBackup -Runs 5 -Label backup
.\scripts\test-local-transport.ps1 -Mode Auto -ShortCode -WarmBackup -Label code
.\scripts\test-local-transport.ps1 -Mode LAN -Only InviteHints -DeadCandidate -Label racing
.\scripts\summarize-local-transport.ps1
```

Swap `-HostSerial` and `-GuestSerial` for reverse orientation. `-Only` isolates InviteHints, Nsd, GatewayProbe, Ipv4Broadcast, Ipv6Multicast or ActiveProbe on the guest; new UDP providers are shared responders on the host. `-StaleHint` substitutes an unreachable address. `-HoldBackupMs 55000` crosses the 30-second keepalive interval and 45-second host idle timeout. All switches live in the separate instrumentation APK or preferences ignored by non-debuggable production builds.

Each successful two-device run asserts authentication, one-item synthetic queue retrieval, remote PLAY, duplicate-command handling, host-state convergence, forced disconnect, successful state retrieval afterward, and cleanup of notification/wake lock/connections. Warm tests additionally require a distinct authenticated backup, reject mutation over that backup, then promote it and preserve host state. The runner checks guest and host completion and retains both-device logcat/crash output. Runs restart the app between sessions; this is a cold-start repetition gate, not a no-restart endurance soak. The synthetic bridge does not alter a real music queue.

44 unit tests pass, including wrong-secret/replay/tampering security tests, invitation bounds/compatibility, packet validation/correlation, subnet calculations, candidate identity/reservations/scoring, cancellation ownership, failure mapping and stale-channel authority. APK assembly passes. Optional `lintDebug` reports 40 errors in existing API/permission compatibility annotations, UI/manifest and local.properties configuration; lint is not a passing gate for this repository. No process binding, Wi-Fi disabling or hotspot activation was added.

Remaining acceptance: real Bluetooth headphones/speaker playback; actual patched YouTube Music native queue edits and internet playback; VPN/lockdown/AP roaming and Wi-Fi-off topology transitions; mixed-version hosts/guests; older Android hardware; many participants; no-restart endurance. The implementation is not claimed release-complete for those environments. Host invitation hints currently advertise IPv4 only, and scoring does not use RTT/instability history.

Detailed measured results are in [LOCAL_TRANSPORT_RESULTS.md](LOCAL_TRANSPORT_RESULTS.md). Raw logs are local ignored build outputs under `build/transport-results`.
