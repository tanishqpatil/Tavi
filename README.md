# Tavi

An Android app planned for local LFM3 text chat, photo questions, and spoken conversations with photo support.

**Status: P1 feasibility harness — 6 November 2026.** A Kotlin/Compose Android project and deterministic local adapter build successfully and its fixture text/photo/audio/cancel flows have run on an ARM64 API 36 emulator. No LFM3 runtime, model payload, microphone/photo capture, model download, physical-device test, Git repository, or publication has been completed. GitHub publication remains a future step.

**Name proposal:** Tavi, pronounced “tah-vee.” Short and independent of a model vendor. This is a working name; trademark, package-name, and repository availability are not established. The workspace directory remains `Omni Android`.

## Recommendation

Use Kotlin and Jetpack Compose. Compare a pinned `llama.cpp`/GGUF adapter with the existing LFM3-Omni LiteRT CompiledModel path before choosing the production runtime. Q4_K_M language weights plus a matching Q8_0 multimodal component remain the GGUF baseline. Android Studio is the IDE, not an alternative to Kotlin or Flutter.

The intended full experience uses LFM3-Omni-2B. Separate LFM3 text and vision packs offer narrower modes without requiring every model to be downloaded. LFM3-Audio is an evaluation candidate, not an extra mandatory download.

These are engineering recommendations, not verified Android compatibility or speed claims. Upstream provides LFM3 support; our Android integration remains untested. See the [official runtime release](https://github.com/ggml-org/llama.cpp/releases/tag/v0.8.0).

## Planning documents

| File | Purpose |
| --- | --- |
| [docs/PLAN.md](docs/PLAN.md) | Product scope, technology comparison, delivery sequence, decisions still needed |
| [docs/PREDEVELOPMENT_REVIEW.md](docs/PREDEVELOPMENT_REVIEW.md) | Similar repositories, success evidence, differentiation, emulator strategy and revised readiness verdict |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, data flow, native boundary, lifecycle, extensibility |
| [docs/TODO.md](docs/TODO.md) | Ordered tasks and evidence-based completion gates |
| [docs/AGENTS.md](docs/AGENTS.md) | Instructions for future contributors and coding agents |
| [docs/BRAIN.md](docs/BRAIN.md) | Decision register, unknowns, and change log |
| [docs/MODELS.md](docs/MODELS.md) | Verified model candidates and download links |
| [docs/DOWNLOADS.md](docs/DOWNLOADS.md) | Future model catalog and robust installation design |
| [docs/VALIDATION.md](docs/VALIDATION.md) | Hardware experiments, test cases, and release gates |
| [docs/PRIVACY_AND_OPEN_SOURCE.md](docs/PRIVACY_AND_OPEN_SOURCE.md) | Local-data behavior, licensing boundaries, GitHub preparation |
| [docs/SOURCES.md](docs/SOURCES.md) | Dated research and primary sources |

## Evidence boundaries

- Official model files and upstream runtime support were researched online.
- No model payload was downloaded or executed. Download redirects, resume behavior, and payload checksums were not tested.
- User confirmed access to Android test devices across 4–12 GB RAM and English-first voice chat. Exact phone models and Android versions remain to be inventoried; no RAM tier is qualified yet.
- App source can be open source. LFM weights have their own license; they must not be described as Apache-2.0 weights. See [Liquid's license guide](https://docs.liquid.ai/lfm/help/model-license).
- The user authorized implementation on 6 November 2026. The initial code is a development-only deterministic adapter; it is not LFM3 inference.
