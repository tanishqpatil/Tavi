package app.lfm3.probe.inference

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

/**
 * Development-only adapter. It proves UI streaming and cancellation deterministically;
 * it must never be represented as LFM3 inference.
 */
class DeterministicProbeEngine : InferenceEngine {
    override val descriptor = EngineDescriptor(
        id = "deterministic-probe",
        capabilities = Capability.entries.toSet(),
        kind = EngineKind.DETERMINISTIC_PROBE,
    )

    override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) {
        val hasImage = request.parts.any { it is RequestPart.Image }
        val hasAudio = request.parts.any { it is RequestPart.Audio }
        val response = buildString {
            append("Probe response. ")
            append(if (hasImage) "The request includes a private photo reference. " else "No photo is attached. ")
            append(if (hasAudio) "The request includes recorded audio metadata. " else "No recorded audio is attached. ")
            append("Replace this adapter only after a pinned LFM3 runtime passes P1.")
        }

        response.split(" ").forEach { word ->
            coroutineContext.ensureActive()
            delay(24)
            emit(InferenceEvent.TextDelta(request.turnId, "$word "))
        }
        emit(InferenceEvent.Completed(request.turnId))
    }
}
