# Tavi

An Android app for local AI conversations with text, photos, and voice. Initial model integration focuses on Liquid AI's LFM3 family.

## Current status

The Kotlin/Compose interface includes Chat, session History, Models, and Settings; light/dark/system themes; reduced motion; and an optional developer panel. The Tavi logo is generated artwork and is included as the app's launcher icon.

The current engine is a deterministic interface preview. Real model inference, verified downloads, persistent conversation storage, camera capture, and voice input/output are still being integrated. Model-library entries are informational and are not installable yet. Token speed requires exact runtime measurements; it is unavailable in preview mode.

Tavi is the project owner's selected name. Other products use this name; no trademark clearance or vendor endorsement is claimed. The technical development application ID remains `app.lfm3.probe` to preserve existing installs.

## Build

Use JDK 17 and Android SDK 36. Set `ANDROID_HOME` or an ignored `local.properties` with your SDK path.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Development

UI code is under `app/src/main/java/app/lfm3/probe/ui/`. The engine contract and adapter are under `inference/`; chat state is under `presentation/`. Theme, developer visibility, and reduced-motion preferences are stored privately on the device. Conversation preview content stays in memory; operational diagnostics exclude conversation payloads.

Internal planning and validation notes are kept locally in the ignored `docs/` directory. No model weights, private media, local SDK paths, build outputs, or signing keys belong in commits. LFM model weights have their own license, separate from application source.
