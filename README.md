# Tavi

<img src="assets/tavi-logo-original.png" alt="Tavi logo" width="96" />

An Android app for local AI conversations with text, photos, and voice. Initial model integration focuses on Liquid AI's LFM3 family.

Built with Kotlin, Jetpack Compose and an embedded C++ runtime. No account or cloud inference. **Early development:** the text path is emulator-tested; multimodal integration and phone qualification are ongoing.

## Try it

1. Build and install the debug APK on an ARM64 Android device (Android 8 or newer).
2. Open **Models**, read the model license, and download LFM3 Text 1.7B. Keep Tavi open during the transfer.
3. Tap **Load model**, then open **Chat**. Model files stay on your device.
4. Enable **Settings → Developer view** for output-token counts, decode speed and first-text timing.

Total device RAM alone does not establish compatibility. No physical-phone performance tier is qualified yet.

## Current status

The Kotlin/Compose interface includes Chat, session History, Models, and Settings; light/dark/system themes; reduced motion; and an optional developer panel. The Tavi logo is generated artwork and is included as the app's launcher icon.

The app now includes an experimental ARM64 CPU adapter for LFM3 Text 1.7B using pinned llama.cpp v0.8.0. Models offers a verified, resumable 1.10 GB Q4_K_M download, followed by explicit load/unload controls. Keep the app open during downloads; they pause when it goes into the background. Complete files are SHA-256 checked before activation and rechecked before loading.

Text uses a 1,024-token context with up to 128 output tokens and greedy sampling for this first correctness experiment. Completed text turns are replayed with the model's chat template; context overflow is rejected visibly. The developer panel reports exact output-token counts and decode rate. Weights are memory-mapped, with an active native context; Android controls page residency.

Verified on the ARM64 API 36 emulator: in-app download with pause/restart/resume, full checksums, offline text/follow-up/cancellation recovery/unload/reload, and a real response through the chat UI. This is experimental text-path evidence; it does not qualify photo/voice or physical-device performance.

The separate interface preview remains available without a model. Persistent conversation storage, camera capture, photo inference and voice input/output are still being integrated. Omni/VL/Audio downloads remain disabled until their multimodal adapters pass validation. No physical-device support or real-time performance is claimed.

Tavi is the project owner's selected name. Other products use this name; no trademark clearance or vendor endorsement is claimed. The technical development application ID remains `app.lfm3.probe` to preserve existing installs.

## Build

Use JDK 17 and Android SDK 36. Set `ANDROID_HOME` or an ignored `local.properties` with your SDK path.

Native source is a pinned Git submodule. Initialize it before building; the Android SDK also needs NDK `28.2.13676358` and CMake `4.1.2`.

```sh
git clone --recurse-submodules https://github.com/tanishqpatil/Tavi.git
cd Tavi
git submodule update --init --recursive
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Development

UI code is under `app/src/main/java/app/lfm3/probe/ui/`. The engine contract and adapter are under `inference/`; chat state is under `presentation/`. Theme, developer visibility, and reduced-motion preferences are stored privately on the device. Conversation content stays in memory; operational diagnostics exclude conversation payloads.

llama.cpp revision: `8642541b255bfb5dc39cd351a178be09013a55d6` (MIT; notice included in app assets). Model revision: `cd0c74c575b5c416f3011b5b4c298077f26ca8c2`. Model terms are linked in the download confirmation and included in the verified pack.

Real-model tests require installing the text pack through the app first. They are opt-in and do not download weights in CI:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lfm3.probe.Lfm3DeviceTest -Pandroid.testInstrumentationRunnerArguments.runModelTest=true
```

Internal planning and validation notes are kept locally in the ignored `docs/` directory. No model weights, private media, local SDK paths, build outputs, or signing keys belong in commits. LFM model weights have their own license, separate from application source.

## License and attribution

Original Tavi application source is licensed under [Apache-2.0](LICENSE). The pinned llama.cpp dependency uses MIT and retains its upstream notice. Model weights are distributed by LiquidAI under their own LFM license; installing this app does not replace those terms. Tavi is independent and is not an official LiquidAI product.
