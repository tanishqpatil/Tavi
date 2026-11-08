# Tavi

[![Android build](https://github.com/tanishqpatil/Tavi/actions/workflows/android.yml/badge.svg)](https://github.com/tanishqpatil/Tavi/actions/workflows/android.yml)

<img src="assets/tavi-logo-original.png" alt="Tavi logo" width="96" />

An Android app for local AI conversations with text, photos, and voice. Initial model integration focuses on Liquid AI's LFM3 family.

Built with Kotlin, Jetpack Compose and an embedded llama.cpp/libmtmd runtime. No account or cloud inference. **Early development:** text is emulator-tested; photo, microphone and speech paths are experimental and require phone testing.

## Try it

1. Download the signed ARM64 APK from [GitHub Releases](https://github.com/tanishqpatil/Tavi/releases), or build it locally. Android 8 or newer is required. Initial releases are development prereleases; phone validation is still pending.
2. Open **Models**, read the model license, and download LFM3 Text 1.7B. Downloads continue in the background; by default they wait for an unmetered network.
3. Tap **Load model**, then open **Chat**. Model files stay on your device.
4. Enable **Settings → Developer view** for output-token counts, decode speed and first-text timing.

For phone test reports, open **Settings → Phone test diagnostics → Preview diagnostics**, review the JSON, then choose **Save report…**. It contains build/device information, selected/loaded pack and context, recent token metrics, memory snapshots and recognized operational event codes. It excludes conversation text/media, server URLs, tool arguments, account/serial identifiers, private paths and keys. Nothing is uploaded automatically. Memory snapshots may be cached by Android and are not peak usage or device-compatibility measurements.

Total device RAM alone does not establish compatibility. No physical-phone performance tier is qualified yet.

## Current status

The Kotlin/Compose interface includes Chat, persistent History, Models, and Settings; light/dark/system themes; reduced motion; and an optional developer panel. The Tavi logo is generated artwork and is included as the app's launcher icon.

Switch pages using the top-right **⋮** navigation menu. The bottom of the chat stays available for messages and the composer.

Models offers resumable, immutable downloads for the following official packs. All required components and the model license must pass SHA-256 verification before activation; files are rechecked at startup and before load. Unload the active model before selecting another pack.

Downloads use persistent user-initiated transfer jobs on Android 14+ and a foreground WorkManager fallback on Android 8–13. Choose **Unmetered networks only** or explicitly allow metered/mobile data in the license dialog. Progress and Pause controls are available in Models and the transfer notification; notification visibility depends on permission. Partial files and transfer intent survive process loss, and resumes validate HTTP ranges and final checksums. Temporary network errors retry with backoff; storage/integrity failures require attention. A model is never loaded automatically when its download finishes.

Android controls scheduling and may defer a transfer after reboot, until unlock/app use, or under system restrictions. On Android 14+, a force-stop clears the scheduled job; reopening shows it paused until you resume. On Android 8–13, WorkManager may reschedule unfinished work when the app is opened; using Pause explicitly preserves the paused intent. Do not expect downloads to bypass Android force-stop, network, battery or thermal restrictions.

| Pack | Approximate download | Intended capabilities | Validation |
| --- | --- | --- | --- |
| LFM3 Text 1.7B | 1.10 GB | Text | Real-model emulator regression passed |
| LFM3 VL 2B | 1.30 GB | Text + photo | Experimental, phone test pending |
| LFM3 Audio 2B | 1.40 GB | Text + voice input + generated speech | Experimental, phone test pending |
| LFM3 Omni 2B | 1.61 GB | Text + photo + voice + speech | Experimental, phone test pending |

Each pack uses its own Q4_K_M language weights, matching Q8_0 multimodal component where required, and pinned license. Equal file sizes do not mean weights can be shared. Downloads exclude compilation/runtime overhead and are not RAM estimates.

**Settings → Generation** offers 1,024 / 2,048 / 4,096 / 8,192 context tokens and output caps from 64 to 2,048 tokens, limited to half the selected context. Context changes require unloading and reloading the model; output changes apply to the next turn. Defaults remain 1,024 / 128. Larger settings are not qualified across device tiers. Greedy text sampling is used for repeatability. Context overflow is rejected visibly. The developer panel reports exact LM output-token counts and decode speed; audio frames are not counted as text tokens. Weights are memory-mapped; Android controls page residency.

Model-provided `<think>…</think>` sections appear in collapsed disclosures with lighter typography. Partial streaming tags are handled without displaying tag fragments. Tavi does not invent a thought trace when the model does not emit one.

Verified on the ARM64 API 36 emulator: full text-pack checksums, real text/follow-up/cancellation recovery/unload/reload, runtime-process death during generation with partial-history recovery, background/screen-off download completion, pause/resume, and persisted job/partial recovery across reboot. Force-stop retained the partial transfer and required explicit resume on reopening. Reboot recovery was deferred by Android's standby/quota state until unlock/app use. This does not qualify photo/voice or physical-device performance; the Android 8–13 transfer fallback is build-checked and still needs its own device/OS validation.

The separate interface preview remains available without a model. Conversations and input photo/voice attachments persist privately in no-backup storage. History supports title/message search, rename, confirmed deletion, and per-conversation ZIP export/restore through Android's file picker. New chat preserves the previous conversation; reopening does not automatically load a model or replay a recording. Streamed replies checkpoint approximately every 500 ms and finish with a completion/interruption status; interrupted replies remain visible and are excluded from follow-up prompts. Generated speech playback itself is not archived.

History lists 30 conversations per page and initially opens the last 40 turns; older messages remain on disk and can be loaded. Follow-ups reconstruct completed turns from the stored conversation, subject to the model's context limits. Conversations are capped at 500 turns. Archives are unencrypted and contain private text/media: choose their destination carefully. Restore validates version, paths, sizes and attachment checksums, creates a separate conversation, and does not overwrite existing media. Archive limits: 500 turns, 64 attachments, 128 MiB of attachments and 4 MiB of metadata. A process crash can lose output since the most recent checkpoint. No physical-device tier, speech quality or real-time performance is qualified yet.

Native inference runs in a private, non-exported `:inference` service process. If that process dies, the UI stays available, marks the reply interrupted and retains its received text. Open **Models → Load model**, then use **Review last question to retry** and press Send when ready. Reload never automatically resends a question, photo or recording, and a failed native session never silently switches to preview output. Cancellation waits for remote shutdown; an unresponsive process is terminated. Model files are protected by OS-backed leases while mapped. This is crash containment, not a guarantee against Android terminating the whole app under memory pressure.

## Optional web search and MCP

**Offline is the default.** In **Settings → Online tools**, choose **Ask first** to review outgoing arguments, or explicitly enable **Auto search**. The on-device text model decides whether to answer locally, search, or use an enabled MCP tool. Each turn permits at most one tool call followed by a local answer. Routing can be wrong; check the returned sources. There is no autonomous task loop or cloud inference.

- **Exa:** ready-to-use keyless search through [Exa MCP](https://exa.ai/mcp). No account/API key needed. Its [MCP server is open source](https://github.com/exa-labs/exa-mcp-server); the hosted search backend is operated by Exa. Free rate limits and availability apply.
- **SearXNG:** fully open-source alternative. Supply your own HTTPS instance base URL with [JSON search enabled](https://docs.searxng.org/dev/search_api.html). Many public instances disable API access; Tavi does not bundle an unverified public instance or scrape around access restrictions.
- **Custom MCP:** enter a keyless HTTPS Streamable HTTP endpoint, connect, then select up to three supported read-only tools. Every custom tool call requires approval, including in Auto search. Read-only annotations are server claims; use a trusted server. Reconnect after restarting Tavi to inspect selected tools again.

This first implementation requires **LFM3 Text**, **2048+ loaded context tokens**, text-only history and questions up to 1200 characters. Change context in Settings, then unload/reload the model. Leave online tools off for photo/voice packs. Tool schemas/evidence consume context; native admission rejects overflow visibly. Hidden routing tokens are excluded from final-answer tokens/second. Actual provider-returned source links and retrieval time persist in History and conversation exports; links are provenance, not proof every generated claim is correct.

Enabling online tools sends the model's query/tool arguments to the chosen provider; these can contain details from your question or recent text. Connecting an MCP endpoint sends protocol/app metadata. This feature does not upload photos, recordings, model weights or the full conversation. Remote providers can apply their own retention policies. Stop/backgrounding cancels the pipeline, but cannot undo a request already received remotely. There are no automatic tool retries or silent provider fallbacks.

MCP support is deliberately limited to initialize-era protocol versions **2025-11-25 / 2025-06-18 / 2025-03-26**, JSON and request-scoped SSE, discovery and text tool results. It is not universal/latest-protocol compatibility. Flat primitive argument schemas only; unsupported tools stay disabled. OAuth/API keys, stdio subprocesses, write tools, subscriptions, sampling and server-initiated requests are not implemented. Broader agentic workflows remain internal analysis only.

## Share into Tavi

Choose **Tavi** in Android's share menu for plain text or one still photo. Review the content, then choose **Import as new draft**. Import preserves the previous conversation/draft and never sends a message, fetches a shared web URL or loads a model automatically. Send explicitly after selecting a compatible pack; photos need VL or Omni. Text is limited to 8,000 characters and photo imports to 20 MiB. Remote/file image URLs, multiple attachments and document parsing are not supported.

Unsent photo/audio attachments are now saved with their private draft references and restored without replaying a recording. Switching chats restores each chat's own attachments. History archives include sent messages/attachments; unsent drafts are retained locally but are not included in those archives.

## Experimental phone testing

For media tests, consider a 4,096-token context and a 256-token output cap as a starting experiment, then reload. This is not a memory/performance guarantee. Short recordings and a single photo are easier to diagnose first.

1. **VL:** select/download/load the vision pack. Use the photo button to choose an image or take a photo through the system camera app. Add a question and send.
2. **Audio:** select/download/load the audio pack. Grant microphone permission, tap to record, then tap again to finish. Press Send. Recording is capped at 30 seconds and stops on backgrounding. Voice input initially enables the **Speak** chip; turn it off before sending if you want text only.
3. **Omni:** attach a photo, record a question about it, and send both in the same turn. The matching model component handles both inputs.
4. **Speech output:** enable **Speak** on an Audio/Omni pack for generated audio. Playback uses bounded blocking writes with no unbounded PCM queue; output also stops at context/output or 30-second audio limits. Stop/backgrounding interrupts generation and flushes playback.

Photos are normalized to a maximum 1,024-pixel edge with orientation handling; audio is recorded as 16 kHz mono WAV. Required media references are replayed in follow-ups rather than replaced with captions. Sessions allow up to eight media attachments; start a new chat when context is full. Audio routing, interruption handling, latency, codec quality and same-turn photo/voice correctness still need physical-device validation.

Generated speech requests transient Android audio focus before playback. Focus loss (including duck requests) or an audio-becoming-noisy event stops the response and flushes playback; regaining focus never automatically replays it. Each response releases its listener/focus lease, and late callbacks are fenced from later responses. Emulator focus-loss/cancellation tests pass; real calls, Bluetooth/headphone routing and speech quality still need phone checks.

Tavi is the project owner's selected name. Other products use this name; no trademark clearance or vendor endorsement is claimed. The technical development application ID remains `app.lfm3.probe` to preserve existing installs.

## Build

### Download from GitHub

**Signed release APK:** open [Releases](https://github.com/tanishqpatil/Tavi/releases), expand the version's **Assets**, and download `Tavi-<version>-arm64.apk` directly. No GitHub login or artifact ZIP extraction is required. `SHA256SUMS.txt` and `BUILD-INFO.txt` accompany the APK. Release assets remain available beyond the Actions artifact retention period. The public signing certificate SHA-256 is recorded in [assets/tavi-release-certificate.sha256](assets/tavi-release-certificate.sha256).

Release APKs use a stable signing key. They cannot update a debug APK signed with another key. Export conversations before choosing to uninstall an incompatible debug build; uninstalling removes its private chats and downloaded models. Future release updates must keep the same signing key and increase the Android version code.

**Debug CI APK:**

1. Open [Android build](https://github.com/tanishqpatil/Tavi/actions/workflows/android.yml) and select a successful run for `main`.
2. Sign in to GitHub and download the **Tavi-arm64-debug-…** artifact under **Artifacts**. Extract the ZIP; it contains the APK, `SHA256SUMS.txt`, and build information. Install the `.apk`, not the ZIP.
3. Artifacts expire after 14 days. Use **Run workflow → main** to build again if you have repository write access.

Pushes to `main`, pull requests, and manual runs build the native ARM64 APK, run JVM tests and Android lint, compile instrumentation tests, and check APK signatures and 16 KB ZIP alignment. Test/lint reports are separate downloadable artifacts. CI does not download model weights or run inference or physical-device tests.

These Actions artifacts are debug builds with temporary keys, separate from signed GitHub Releases. Android may refuse an update over a locally built or older CI APK signed with a different key. Export history before uninstalling an incompatible build.

### Build locally

Use JDK 17 and Android SDK 36. Set `ANDROID_HOME` or an ignored `local.properties` with your SDK path.

Native source is a pinned Git submodule. Initialize it before building; the Android SDK also needs NDK `28.2.13676358` and CMake `4.1.2`.

```sh
git clone --recurse-submodules https://github.com/tanishqpatil/Tavi.git
cd Tavi
git submodule update --init --recursive
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

### Publishing a signed release

The [Signed Android release workflow](https://github.com/tanishqpatil/Tavi/actions/workflows/release.yml) runs on `v*` tags or a manual run targeting an existing version tag. Set `taviVersionName` and an increased `taviVersionCode` in `gradle.properties`, commit/push to `main`, then create/push the matching tag (for example `v0.1.0-alpha.1`). The workflow verifies that the tag matches the source version and is on `main` history.

Repository secrets: `TAVI_SIGNING_KEYSTORE_BASE64` (PKCS12), `TAVI_SIGNING_STORE_PASSWORD`, `TAVI_SIGNING_KEY_ALIAS`, and `TAVI_SIGNING_KEY_PASSWORD`. Private signing material is never committed or uploaded as an artifact. Local signed builds read `TAVI_SIGNING_KEYSTORE` (file path) and the three password/alias environment variables; without them, a local release build is unsigned and cannot pass publication verification.

CI runs the shared JVM tests through `testDebugUnitTest` and release lint, builds the signed release, verifies its certificate against the public fingerprint, checks application ID/version/non-debuggable status, ARM64 packaging and 16 KB ZIP alignment, then uploads a draft's assets before publishing. Tags containing a prerelease suffix are marked **Pre-release**. Already-published assets are not overwritten. Phone inference/media qualification remains separate from signing and packaging checks.

## Development

UI code is under `app/src/main/java/app/lfm3/probe/ui/`. The engine contract and adapter are under `inference/`; chat state is under `presentation/`; transactional SQLite persistence and bounded archives are under `history/`. Theme, developer visibility, and reduced-motion preferences are stored privately on the device. Operational diagnostics exclude conversation payloads.

llama.cpp revision: `8642541b255bfb5dc39cd351a178be09013a55d6` (MIT; native media notices included in app assets). Exact model revisions, sizes and checksums are in `ModelCatalog.kt` and `VerifiedArtifact.kt`. Model terms are linked in the download confirmation and included in each verified pack.

Device tests are opt-in; real-model tests require the text pack installed through the app first. Build both APKs, update-install them with `adb install -r`, then invoke the runner directly to preserve app data. Some connected-test tooling uninstalls the app afterward, removing private chats and downloaded models. CI runs JVM tests and compiles device tests without downloading weights.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class app.lfm3.probe.ConversationStorageTest app.lfm3.probe.test/androidx.test.runner.AndroidJUnitRunner
# Opt-in: installed text weights required; kills only the private inference process.
adb shell am instrument -w -e class app.lfm3.probe.RuntimeRecoveryTest -e runModelTest true app.lfm3.probe.test/androidx.test.runner.AndroidJUnitRunner
# Tool fixtures (no network by default); add -e runOnlineTest true for the public keyless search check.
adb shell am instrument -w -e class app.lfm3.probe.OnlineToolsTest app.lfm3.probe.test/androidx.test.runner.AndroidJUnitRunner
# Opt-in: installed text model, routing decisions, then live search and local answer.
adb shell am instrument -w -e class app.lfm3.probe.OnlineRoutingDeviceTest -e runModelTest true -e runOnlineTest true app.lfm3.probe.test/androidx.test.runner.AndroidJUnitRunner
```

`BackgroundDownloadTest` with `runDownloadTest=true` is a separate development-device test: it truncates/re-downloads part of the verified text pack, tests notification pause/resume, and temporarily backgrounds the app and turns the screen off. Do not run it against a device/session whose current state you need to preserve. Manual restart-test flags are explicitly guarded in its source.

Internal planning and validation notes are kept locally in the ignored `docs/` directory. No model weights, private media, local SDK paths, build outputs, or signing keys belong in commits. LFM model weights have their own license, separate from application source.

## License and attribution

Original Tavi application source is licensed under [Apache-2.0](LICENSE). The pinned llama.cpp dependency uses MIT and retains its upstream notice. Model weights are distributed by LiquidAI under their own LFM license; installing this app does not replace those terms. Tavi is independent and is not an official LiquidAI product.
