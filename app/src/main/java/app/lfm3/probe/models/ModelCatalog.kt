package app.lfm3.probe.models

/** All files in a pack are versioned and verified together; components are never shared between checkpoints. */
data class ModelPack(
    val id: String,
    val title: String,
    val revision: String,
    val capabilities: Set<ModelCapability>,
    val weights: VerifiedArtifact,
    val projector: VerifiedArtifact?,
    val license: VerifiedArtifact,
    val runtime: ModelRuntime = ModelRuntime.GGUF,
    val additionalArtifacts: List<VerifiedArtifact> = emptyList(),
    /** Space reserved for extracted graphs and packed weights, in addition to the download. */
    val workingBytes: Long = 0,
) {
    val artifacts get() = listOfNotNull(weights, projector, license) + additionalArtifacts
    val bytes get() = artifacts.sumOf { it.bytes }
    val directory get() = "${if (id == "lfm3-text") "text" else id}-$revision"
    /** A resumed job cannot silently adopt different quantization/components at the same revision. */
    val fingerprint: String by lazy {
        val manifest = "$id:$revision:" + artifacts.joinToString(";") { "${it.name}:${it.bytes}:${it.sha256}" }
        java.security.MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    val licensePage get() = license.url.replace("/resolve/", "/blob/")
    val experimental get() = projector != null || runtime == ModelRuntime.LITERT
}
enum class ModelRuntime { GGUF, LITERT }
enum class ModelCapability { TEXT, PHOTO, VOICE_INPUT, VOICE_OUTPUT }

object ModelCatalog {
    val packs = listOf(
        ModelPack("lfm3-text", "LFM3 Text 1.7B", TextPack.revision, setOf(ModelCapability.TEXT), TextPack.weights, null, TextPack.license),
        ModelPack("lfm3-vl", "LFM3 VL 2B", "1add221a5ea6c047fcf1eaaf2d247939d7cf8849", setOf(ModelCapability.TEXT, ModelCapability.PHOTO),
            VerifiedArtifact("LFM3-VL-2B-Q4_K_M.gguf", 1099627808L, "56dac2e192ff0d9ab8507f4f1aeb4aed0463449bbbefaac79497f041c95f21eb", "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/1add221a5ea6c047fcf1eaaf2d247939d7cf8849/LFM3-VL-2B-Q4_K_M.gguf"),
            VerifiedArtifact("mmproj-LFM3-VL-2B-Q8_0.gguf", 203031872L, "c5c5b58d544a18da919172d2d0368090ea1a4a351b6feb7f8a326a264a5918ab", "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/1add221a5ea6c047fcf1eaaf2d247939d7cf8849/mmproj-LFM3-VL-2B-Q8_0.gguf"),
            VerifiedArtifact("LICENSE", 10574L, "30adf9d6478191fb87f2424f63ba0728598335aaf99cd2848ef17e8e545fe94b", "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/1add221a5ea6c047fcf1eaaf2d247939d7cf8849/LICENSE")),
        ModelPack("lfm3-audio", "LFM3 Audio 2B", "39670cb5c42e9c4bfe046519ba7ffbe47f9552ad", setOf(ModelCapability.TEXT, ModelCapability.VOICE_INPUT, ModelCapability.VOICE_OUTPUT),
            VerifiedArtifact("LFM3-Audio-2B-Q4_K_M.gguf", 1099626016L, "9a773289bd321ed879cd3a76545f6aca506e403851a45fb315420aee45625be2", "https://huggingface.co/LiquidAI/LFM3-Audio-2B-GGUF/resolve/39670cb5c42e9c4bfe046519ba7ffbe47f9552ad/LFM3-Audio-2B-Q4_K_M.gguf"),
            VerifiedArtifact("mmproj-LFM3-Audio-2B-Q8_0.gguf", 303811840L, "2b6648b1fd48af16aa0877b0592b144d15070783673e038a379fdbc51d40e365", "https://huggingface.co/LiquidAI/LFM3-Audio-2B-GGUF/resolve/39670cb5c42e9c4bfe046519ba7ffbe47f9552ad/mmproj-LFM3-Audio-2B-Q8_0.gguf"),
            VerifiedArtifact("LICENSE", 10574L, "4d28ca14dedc0b3d0fcc2b3339f0e79931faa33874f3d24f522183a8fc70068c", "https://huggingface.co/LiquidAI/LFM3-Audio-2B-GGUF/resolve/39670cb5c42e9c4bfe046519ba7ffbe47f9552ad/LICENSE")),
        ModelPack("lfm3-omni", "LFM3 Omni 2B", "d508b88f08a5b4286f2d2805a6a8d69a0ff44c72", ModelCapability.entries.toSet(),
            VerifiedArtifact("LFM3-Omni-2B-Q4_K_M.gguf", 1099630080L, "9f8d30e9d4fd7a9349d586e077988229b3ca0a0e4ad730897563d1eccc9893e2", "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/d508b88f08a5b4286f2d2805a6a8d69a0ff44c72/LFM3-Omni-2B-Q4_K_M.gguf"),
            VerifiedArtifact("mmproj-LFM3-Omni-2B-Q8_0.gguf", 506842528L, "8a25f9d1931244429de404fccacfb7fb933970a36aa5688d2f5069f29c6b27b1", "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/d508b88f08a5b4286f2d2805a6a8d69a0ff44c72/mmproj-LFM3-Omni-2B-Q8_0.gguf"),
            VerifiedArtifact("LICENSE", 10574L, "4d28ca14dedc0b3d0fcc2b3339f0e79931faa33874f3d24f522183a8fc70068c", "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/d508b88f08a5b4286f2d2805a6a8d69a0ff44c72/LICENSE")),
    ) + if (app.lfm3.probe.BuildConfig.LITERT_EXPERIMENTAL) listOf(LiteRtPack.pack) else emptyList()
    val text get() = packs.first()
    fun get(id: String) = packs.first { it.id == id }
}
