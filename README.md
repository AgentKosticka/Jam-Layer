# Jam Layer

Jam Layer provides pairing, nearby discovery, encrypted transport and session lifetime for native YouTube Music queue sharing. Host and guest devices keep using YouTube Music's own queue; Jam Layer does not stream audio.

The matching optional YouTube Music patch targets **9.15.51**. The patch is available in [the Jam queue sharing patch source](https://github.com/AgentKosticka/Jam-Patches/releases). The upstream contribution is tracked in [Morphe patches](https://github.com/MorpheApp/morphe-patches/pull/3014).

## Install

Download the latest signed `app-release.apk` from [Releases](https://github.com/AgentKosticka/Jam-Layer/releases). Install it, then install a compatible YTM 9.15.51 ARM64 build with the Jam queue sharing patch. Pair from the Jam row embedded in YTM.

## Build

Requires JDK 25 and Android SDK 37. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` for local development. Release builds are signed in GitHub Actions using repository Actions secrets; no signing key belongs in source control.

Companion forks may use their own signing keys. Pairing authorizes the selected
package with a locally generated capability token.

## Scope and security

This is an experimental development project for YouTube Music 9.15.51 and Android API 26+. Invitations are bearer credentials. The transport authenticates and encrypts session traffic. The implementation has not received an independent security audit.


## Bridge compatibility

The authenticated local Binder bridge advertises protocol version 1, with queue
revision, stable item ID and stale edit rejection capabilities. Legacy peers
without an envelope remain compatible with v1. A present but malformed envelope,
an unsupported required version, or missing required capabilities is rejected
before dispatch. This addition does not change the encrypted network framing.

Pairing records the selected music app package and capability token. Re-pair after
changing either selected package.

Version 1.0.2 forwards participant next/previous requests through the authenticated
host command path. Use it with the matching Jam patch update on both devices.
These controls respect the host's guest-edit permission; they never request
local playback on the participant.

Release acceptance requires manual tests on two real devices with the exact patch
build: pairing, queue revision convergence, stale edits, reconnect, Aware and LAN
connections, package rejection and invitation expiry. Unit tests and APK installation
alone do not satisfy this gate. The published custom source must also pass a clean
Morphe Manager consumer test before the coordinated release is considered complete.

## VPNs and local networking

Jam Layer never process-binds the app away from a VPN. On Android versions that
support it, it discovers services and opens LAN sockets on the physical Wi-Fi or
Ethernet network explicitly; system routing is only a fallback for a VPN's own
split-route policy.

No special setup should normally be needed with a partial-tunnel VPN. If an
exit-node or lockdown VPN blocks LAN access, enable that VPN's LAN-access option
or exclude Jam Layer through its app split-tunneling controls. A non-bypassable
Android VPN can intentionally prohibit local networking; Jam Layer reports that
case rather than trying to defeat the policy.
