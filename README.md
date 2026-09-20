# Jam Layer

Jam Layer provides pairing, nearby discovery, encrypted transport and session lifetime for native YouTube Music queue sharing. Host and guest devices keep using YouTube Music's own queue; Jam Layer does not stream audio.

The matching optional YouTube Music patch targets **9.15.51**. The patch is available in [the Jam queue sharing patch source](https://github.com/AgentKosticka/Jam-Patches/releases). The upstream contribution is tracked in [Morphe patches](https://github.com/MorpheApp/morphe-patches/pull/3014).

## Install

Download the latest signed `app-release.apk` from [Releases](https://github.com/AgentKosticka/Jam-Layer/releases). Install it, then install a compatible YTM 9.15.51 ARM64 build with the Jam queue sharing patch. Pair from the Jam row embedded in YTM.

## Build

Requires JDK 25 and Android SDK 37. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` for local development. Release builds are signed in GitHub Actions using repository Actions secrets; no signing key belongs in source control.

The release signer certificate SHA-256 is pinned in the companion and matching Morphe patch. Replacing the signing key requires updating that pin and distributing a compatible patch build.

## Scope and security

This is an experimental development project for YouTube Music 9.15.51 and Android API 26+. Invitations are bearer credentials. The transport authenticates and encrypts session traffic. The implementation has not received an independent security audit.


## Bridge compatibility

The authenticated local Binder bridge advertises protocol version 1, with queue
revision, stable item ID and stale edit rejection capabilities. Legacy peers
without an envelope remain compatible with v1. A present but malformed envelope,
an unsupported required version, or missing required capabilities is rejected
before dispatch. This addition does not change the encrypted network framing.

Pairing records the selected music app's signing certificate and capability token.
The music extension pins the Companion release certificate. Re-pair after upgrading
from a development build that did not record a signer. A debug-signed Companion
cannot substitute for the pinned release build.

Release acceptance requires manual tests on two real devices with the exact patch
build: pairing, queue revision convergence, stale edits, reconnect, Aware and LAN
connections, signer rejection and invitation expiry. Unit tests and APK installation
alone do not satisfy this gate. The published custom source must also pass a clean
Morphe Manager consumer test before the coordinated release is considered complete.
