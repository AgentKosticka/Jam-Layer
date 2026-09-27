# Local transport results — 2026-09-27

## QR/code follow-up

The prior broadcast-isolation run left `disableAware=true` and `disableBle=true` in persistent debug preferences on both devices. This suppressed normal QR fallback afterward. Overrides now exist only in the instrumentation process; legacy preferences are ignored. Each fixture verifies that persisted disable flags cannot affect normal defaults.

Code pairing now shares broadcast, IPv6 multicast and bounded active discovery, with normalized/staggered/cancellable TCP candidates. Aware code discovery no longer advertises a code hash or uses a code-derived data-path passphrase. Its versioned public-session discovery requires updated peers; PAKE remains authoritative.

User-controlled setup: phone joined to its existing Wi-Fi, tablet disconnected from infrastructure Wi-Fi with its Wi-Fi radio enabled. Direct Aware invitation joins passed in both orientations (1728/1663 ms; reconnect 2111/2071 ms). Updated Aware code discovery passed in both orientations (1381/1446 ms; reconnect 2497/2497 ms). Normal Auto with all providers enabled passed for invitations (1520 ms, reconnect 2628 ms) and code (1617 ms, reconnect 4604 ms). These exercise the invitation join path, not the camera UI.

After the user reconnected the tablet to the same LAN:

| Short-code discovery | Phone host join/reconnect (ms) | Tablet host join/reconnect (ms) |
| --- | --- | --- |
| Broadcast only | 641 / 1208 | 385 / 2277 |
| IPv6 multicast only | 691 / 1075 | 685 / 1335 |
| Active probe only | 4945 / 4642 | 4425 / 3326 |

Auto warm-backup checks passed for code (1829 ms join, 41 ms promotion/resync) and invitation (668 ms join, 42 ms promotion/resync). All passing runs assert encrypted commands, duplicate handling, synthetic queue state, forced recovery and cleanup. The first broadcast test overlapped the user disconnecting the tablet and timed out; its logs remain under `fix-code-broadcast`, excluded from the same-LAN checks above.

47 unit tests pass and both APKs assemble. Final installed debug APK SHA-256: `97924D3B9C25F91248F37F54D358492AEFD25650EE2D753D90CA2F8AF654672E`. Reverse LAN-provider runs and the invitation backup check used this final build; earlier follow-up runs preceded only the separation of pairing provenance from normal reconnect targets. Saved user pairing was backed up before fixture runs for restoration afterward. These are targeted regression checks, not a rerun of the complete release gate or real YTM/audio validation.

## Earlier implementation gate

Measured on A069P (Android 37) and SM-X620 (Android 36), same Wi-Fi, both host orientations. Times include authentication. Recovery includes state retrieval after a forced socket close; cold recovery also includes the existing failure-detection/retry schedule. Warm tests actively trigger failure detection and include promotion/state resync. These are small-sample observations, not guaranteed latency.

| Scenario | Passed / attempted | Join median / worst (ms) | Recovery median / worst (ms) |
| --- | --- | --- | --- |
| final-auto | 20 / 20 | 794.5 / 1589 | 1257.5 / 2627 |
| final-lan | 20 / 20 | 290 / 1387 | 902 / 1886 |
| final-aware | 10 / 10 | 1758.5 / 2006 | 3396 / 4924 |
| final-broadcast | 10 / 10 | 1215.5 / 24035 | 1062.5 / 2083 |
| final-active | 5 / 5 | 5003 / 5127 | 928 / 1336 |
| final-stale | 5 / 5 | 588 / 1076 | 1022 / 1849 |
| final-backup | 10 / 10 | 434.5 / 1201 | 47 / 526 |
| final-code | 4 / 4 | 1551 / 1991 | 205.5 / 523 |
| hints | 4 / 4 | 141.5 / 408 | 1047.5 / 1702 |
| ipv6 | 4 / 4 | 600 / 4088 | 875.5 / 1144 |
| racing | 3 / 3 | 186 / 188 | 824 / 1323 |
| keepalive | 1 / 1 | 911 / 911 | 60 / 60 |

`final-*` labels are successful post-fix gates. The NSD cleanup crash found during development is preserved separately under `gate-auto`; it is not counted as a passing run. The final 10 backup tests also prove that backup-channel mutation is rejected before promotion. A 55-second hold crossed the keepalive interval and host idle timeout successfully. Short-code joins use the same primary/backup authority path.

The broadcast gate used the earlier eight-second retry ceiling and recorded a 24-second worst case. The delivered build reduces that ceiling to two seconds; a separate two-orientation smoke check is recorded below. Other gates span incremental builds as documented in TRANSPORT_TEST_MATRIX.md; they were not all rerun after that isolated retry-timing change.

Build: 44 unit tests pass; debug app and instrumentation APK assemble. Optional Android lint remains failing with 40 existing compatibility/configuration findings. The installed debug app SHA-256 is `A38DF878927187141C60E441214E0F82BCBE0A71E94D664E774F8DE2AF572223`.

Raw evidence is in the ignored `build/transport-results` directory. Tests used a synthetic bridge, restarted processes between sessions, and checked notification/wake-lock/connection cleanup. These results do not validate real music playback, native queue edits, Bluetooth audio quality, AP roaming, VPN transitions or no-restart endurance. Wi-Fi associations remained intact, internet ping worked on both devices, and phone mobile data stayed enabled.

Testing reset Jam Layer app data/pairing on both devices. Re-pair from YouTube Music for normal use. Existing uncommitted DeviceScenario/LanSocketScenario work was preserved. No commit, push or release was performed.

Final installed-build smoke: broadcast-only joins passed in both orientations (1986 / 2831 ms; recovery 1003 / 1261 ms) with the two-second retry ceiling. BLE software audio-guard tests passed on both devices: injected audio activity blocks/tears down BLE and safe resume works without connected audio hardware. The phone initially failed the old harness's unnecessary service-start precondition; moving the independent radio-policy test ahead of service startup fixed the harness, and the targeted rerun passed. This does not establish real headphone playback quality.
