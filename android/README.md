# Cleaner Android

Native Kotlin / Jetpack Compose app. See [../docs/architecture.md](../docs/architecture.md).

## Build

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Permissions setup

1. Enable **Cleaner** in Accessibility settings.
2. Tap **Start filter** and approve screen capture (+ microphone for audio filter).

## Model

Optional: add `app/src/main/assets/models/nsfw_gate.tflite` (MobileNet NSFW gate). Without it, a lightweight heuristic gate is used.
