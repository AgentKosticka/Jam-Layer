# Session lifecycle validation — 26 September 2026

Built the debug companion and instrumentation APK, wiped the previous companion,
and streamed-installed both on Nothing A069P (API 37) and Samsung SM-X620 (API 36).
Tests use a synthetic `IJamBridge` inside the separate test APK; they do not mutate
YTM queues and do not validate the patch's native UI or playback hooks.

- All 30 JVM unit tests pass, including socket closure interrupting a blocked writer.
- `layerLifecycle` passes on both devices: HOST/END return session state; Leave
  completes in under the 750 ms assertion budget while a native snapshot is
  blocked; the late snapshot is discarded; cancelled code lookup returns within
  two seconds; immediate replacement hosting survives old cleanup; expiry and
  pairing-only starts remove the foreground notification; Leave removes the
  invitation, connections and wake lock.
- Phone host / tablet guest: LAN short-code join, encrypted snapshot and command,
  duplicate command, forced disconnect/reconnect and cleanup pass. Final measured
  join: 1,039 ms.
- Tablet host / phone guest: Aware short-code join, the same command/reconnect
  checks and cleanup pass. Final measured join: 2,451 ms.
- Phone Wi-Fi off: forced BLE short-code join and command/reconnect checks pass
  on the earlier build in this change (6,619 ms). Final Auto build also passes
  with Wi-Fi off (8,487 ms). Bluetooth discovery remains hardware-dependent;
  these timings are individual observations, not latency guarantees.
- The first forced-LAN attempt failed while the phone's Wi-Fi was off. Enabling
  Wi-Fi and confirming a common LAN resolved that test setup limitation.

Local logs are under the workspace `analysis/jam-layer-*` and
`analysis/jam-session-layer-final.log`. Test APKs and test pairing state are
removed after validation, leaving the updated debug companion ready to pair.

For manual patch acceptance, use Jam-Patches `docs/session-lifecycle-testing.md`.
