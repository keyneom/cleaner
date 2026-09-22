# Cleaner

On-device live content filter for Android. Mirrors the screen through a safety-gated accessibility overlay with click-through to the real app.

## Download

Signed APKs are published on [GitHub Releases](https://github.com/keyneom/cleaner/releases/latest) (tag `v*`).

Because the app is sideloaded (not Play Store), Android will ask you to allow installation from your browser or file manager. Download the APK and matching `.sha256` from the same release.

## Requirements

- Android 10+ (API 29); Android 14+ recommended for single-app screen capture
- 64-bit ARM (`arm64-v8a`)
- Accessibility + screen capture permissions (in-app setup)

## Features

- **Visual filter** — NudeNet-style on-device detector (not OpenJev). Classifies the newest frame only and keeps the last finished picture up, so a slow model drops frames instead of adding lag.
- **Text filter** — accessibility tree + ML Kit OCR + word lists
- **DNS filter** — Cloudflare Family / NextDNS / CleanBrowsing (Private DNS or local DNS VPN)
- **Audio filter** — delayed replay with keyword spotting (synced to video presentation clock)
- **Parental PIN** — lock settings changes

All ML runs on-device. Screen content is not uploaded.

See [docs/architecture.md](docs/architecture.md) for design details.

## Build locally

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
cd android
./gradlew assembleDebug
```

## Release

Tag `vX.Y.Z` matching `versionName` in `android/app/build.gradle.kts`. The release workflow matches EasyBC and Keyweb: a `v*` tag builds a signed APK, verifies it with `apksigner`, writes a `.sha256`, and publishes a GitHub Release. Install that APK directly; the app is not on the Play Store.

The `release` GitHub environment needs `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
