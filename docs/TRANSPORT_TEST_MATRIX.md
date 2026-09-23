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
