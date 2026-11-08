package app.lfm3.probe.inference

/**
 * Stable boundary for a local LFM3 engine. It has no network capability; a production
 * GGUF or LiteRT implementation can replace the deterministic probe without changing UI code.
 */
interface InferenceEngine {
    val descriptor: EngineDescriptor
    fun prepare(): Long = 0
    fun cancel() {}

    suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit)
}

data class EngineDescriptor(
    val id: String,
    val capabilities: Set<Capability>,
    val kind: EngineKind,
)

enum class EngineKind { DETERMINISTIC_PROBE, LITERT, LLAMA_CPP }

enum class Capability { TEXT, IMAGE, AUDIO_INPUT, AUDIO_OUTPUT }

data class InferenceRequest(
    val turnId: Long,
    val parts: List<RequestPart>,
    val history: List<ChatMessage> = emptyList(),
    val generationTicket: Long = 0,
    val outputLimit: Int = 128,
    val speakResponse: Boolean = false,
    val systemInstruction: String = "",
    /** Host-owned routing grammar only; never accept arbitrary grammar from a server/model. */
    val routingJson: Boolean = false,
)

data class ChatMessage(val role: String, val text: String, val media: List<RequestPart> = emptyList())

sealed interface RequestPart {
    data class Text(val value: String) : RequestPart
    /** A private-file token, not a URI or a user path. */
    data class Image(val privateReference: String) : RequestPart
    /** The future recorder supplies private PCM metadata through this type. */
    data class Audio(val privateReference: String, val durationMs: Long) : RequestPart
}

sealed interface InferenceEvent {
    /** Exact output-token count and decode duration reported by the runtime, never word estimates. */
    data class Usage(val turnId: Long, val outputTokens: Int, val decodeNanos: Long,
                     val audioSamples: Long = 0, val audioRate: Int = 0, val audioPeak: Float = 0f,
                     val firstAudioMs: Long? = null) : InferenceEvent
    data class TextDelta(val turnId: Long, val value: String) : InferenceEvent
    data class Completed(val turnId: Long, val outputLimitReached: Boolean = false) : InferenceEvent
    data class Failed(val turnId: Long, val reason: String) : InferenceEvent
}
