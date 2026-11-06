package app.lfm3.probe.models

/** A model is visible before it is installable; missing immutable metadata blocks download. */
data class ModelPack(
    val id: String,
    val title: String,
    val capabilities: Set<ModelCapability>,
    val status: PackStatus,
    val reason: String,
)

enum class ModelCapability { TEXT, PHOTO, VOICE_INPUT, VOICE_OUTPUT }
enum class PackStatus { NOT_CATALOGED, READY_TO_DOWNLOAD, DOWNLOADING, INSTALLED, FAILED }

object ModelCatalog {
    val packs = listOf(
        ModelPack("lfm3-omni", "LFM3 Omni 2B", ModelCapability.entries.toSet(), PackStatus.NOT_CATALOGED, "Artifact manifest is not yet pinned and verified."),
        ModelPack("lfm3-vl", "LFM3 VL 2B", setOf(ModelCapability.TEXT, ModelCapability.PHOTO), PackStatus.NOT_CATALOGED, "Artifact manifest is not yet pinned and verified."),
        ModelPack("lfm3-audio", "LFM3 Audio 2B", setOf(ModelCapability.TEXT, ModelCapability.VOICE_INPUT, ModelCapability.VOICE_OUTPUT), PackStatus.NOT_CATALOGED, "Artifact manifest is not yet pinned and verified."),
    )
}
