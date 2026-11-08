package app.lfm3.probe.models

/** Reviewed community conversion; never interchangeable with LiquidAI's GGUF components. */
internal object LiteRtPack {
    private const val revision = "6d95b0222f6724130b7cfdff9e63ed65982bcb77"
    private const val base = "https://huggingface.co/litert-community/LFM3-Omni-2B-LiteRT/resolve/$revision/"
    private fun file(name: String, bytes: Long, hash: String, source: String = name) =
        VerifiedArtifact(name, bytes, hash, base + source)
    val pack = ModelPack(
        "lfm3-omni-litert", "LFM3 Omni 2B · LiteRT INT8", revision, ModelCapability.entries.toSet(),
        file("LFM3-Omni-2B_int8.litertlm", 3226750192, "959617973a7ddcb3d887fa0d9891d79556a266490d5c432b02a18ffcac3124ed"),
        null,
        file("LICENSE", 10574, "4d28ca14dedc0b3d0fcc2b3339f0e79931faa33874f3d24f522183a8fc70068c"),
        runtime = ModelRuntime.LITERT,
        additionalArtifacts = listOf(
            file("LFM3-Omni-2B_depth_frame2_f32.tflite", 435046636, "19646b570f453711a60add2523febb4884e3214e8c3b7f5ca898dc29dae3d60a", "speech/LFM3-Omni-2B_depth_frame2_f32.tflite"),
            file("LFM3-Omni-2B_frame_embed_f32.tflite", 198281972, "71b68529124ef770a495554ea6928f42a66c8c0f2756df2452b15b22bdbc707d", "speech/LFM3-Omni-2B_frame_embed_f32.tflite"),
            file("LFM3-Omni-2B_codec_decoder_f12_f32.tflite", 152370912, "7134523fb54c2fd9b31697373b37e1a5bd1669c3a432ab7896a8a7d070f38ed3", "speech/LFM3-Omni-2B_codec_decoder_f12_f32.tflite"),
        ),
        workingBytes = 7_000_000_000,
    )
}
