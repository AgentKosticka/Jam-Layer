# Jam Layer

Jam Layer provides pairing, nearby discovery, encrypted transport and session lifetime for native YouTube Music queue sharing. Host and guest devices keep using YouTube Music's own queue; Jam Layer does not stream audio.

The matching optional YouTube Music patch targets **9.15.51**. The patch is available in [the Jam queue sharing patch source](https://github.com/AgentKosticka/Jam-Patches/releases). The upstream contribution is tracked in [Morphe patches](https://github.com/MorpheApp/morphe-patches/pull/3014) once opened.

## Install

Download the latest signed `Jam-Layer.apk` from [Releases](https://github.com/AgentKosticka/Jam-Layer/releases). Install it, then install a compatible YTM 9.15.51 build with the Jam queue sharing patch. Pair from the Jam row embedded in YTM.

## Build

Requires JDK 25 and Android SDK 37. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` for local development. Release builds are signed in GitHub Actions using repository Actions secrets; no signing key belongs in source control.

The release signer certificate SHA-256 is pinned in the companion and matching Morphe patch. Replacing the signing key requires updating that pin and distributing a compatible patch build.

## Scope and security

This is an experimental development project for YouTube Music 9.15.51 and Android API 26+. Invitations are bearer credentials. The transport authenticates and encrypts session traffic. The implementation has not received an independent security audit.

