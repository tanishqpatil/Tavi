package app.lfm3.probe.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lfm3.probe.inference.DeterministicProbeEngine
import app.lfm3.probe.inference.InferenceEngine
import app.lfm3.probe.inference.InferenceEvent
import app.lfm3.probe.inference.InferenceRequest
import app.lfm3.probe.inference.RequestPart
import app.lfm3.probe.inference.ChatMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProbeViewModel(
    private val engine: InferenceEngine = DeterministicProbeEngine(),
) : ViewModel() {
    private val _state = MutableStateFlow(ProbeUiState())
    val state: StateFlow<ProbeUiState> = _state.asStateFlow()
    private var generation: Job? = null
    private var nextTurnId = 1L
    private var activeTurnId: Long? = null
    private var startedAt = 0L
    private val completedTurns = mutableSetOf<Long>()

    fun newChat() {
        cancel()
        completedTurns.clear()
        _state.value = ProbeUiState(engineName = engine.descriptor.id,
            realInference = engine.descriptor.kind != app.lfm3.probe.inference.EngineKind.DETERMINISTIC_PROBE)
    }

    fun updateDraft(value: String) = _state.update { it.copy(draft = value.take(8_000)) }

    fun togglePhoto() = _state.update { it.copy(photoAttached = !it.photoAttached) }

    fun toggleAudio() = _state.update { it.copy(audioAttached = !it.audioAttached) }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.isGenerating || (snapshot.draft.isBlank() && !snapshot.photoAttached && !snapshot.audioAttached)) return
        val turnId = nextTurnId++
        startedAt = System.nanoTime()
        val requestParts = buildList {
            if (snapshot.draft.isNotBlank()) add(RequestPart.Text(snapshot.draft.trim()))
            if (snapshot.photoAttached) add(RequestPart.Image("fixture-photo"))
            if (snapshot.audioAttached) add(RequestPart.Audio("fixture-audio", durationMs = 5_000))
        }
        _state.update {
            it.copy(
                // Bound the preview session while durable, paginated history is being integrated.
                turns = it.turns.takeLast(198) + ProbeTurn.User(turnId, snapshot.draft, snapshot.photoAttached, snapshot.audioAttached) + ProbeTurn.Assistant(turnId, ""),
                draft = "",
                photoAttached = false,
                audioAttached = false,
                isGenerating = true,
                error = null,
                trace = listOf("Request accepted", "Engine: ${engine.descriptor.id}", "Parts: ${requestParts.size}"),
                engineName = engine.descriptor.id,
                realInference = engine.descriptor.kind != app.lfm3.probe.inference.EngineKind.DETERMINISTIC_PROBE,
                metrics = GenerationMetrics(),
            )
        }
        completedTurns.retainAll(_state.value.turns.map { it.id }.toSet())
        activeTurnId = turnId
        val generationTicket = engine.prepare()
        generation = viewModelScope.launch {
            try {
                val history = snapshot.turns.filter { it.id in completedTurns }.map {
                    when (it) {
                        is ProbeTurn.User -> ChatMessage("user", it.text)
                        is ProbeTurn.Assistant -> ChatMessage("assistant", it.text)
                    }
                }
                engine.generate(InferenceRequest(turnId, requestParts, history, generationTicket)) { event -> withContext(Dispatchers.Main.immediate) { handleEvent(event) } }
            } catch (_: CancellationException) {
                // Cancellation is expected. A future native adapter must stop native work here too.
            } catch (e: Exception) {
                if (activeTurnId == turnId) _state.update { it.copy(error = if (e is IllegalStateException) e.message else "The response was interrupted. Please retry.") }
            } finally {
                if (activeTurnId == turnId) {
                    activeTurnId = null
                    _state.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    fun cancel() {
        engine.cancel()
        activeTurnId = null // A late callback from a cancelled engine cannot alter a later turn.
        generation?.cancel()
        generation = null
        _state.update { it.copy(isGenerating = false, error = "Response stopped.", trace = (it.trace + "Cancellation acknowledged").takeLast(32)) }
    }

    private fun handleEvent(event: InferenceEvent) {
        val eventTurnId = when (event) {
            is InferenceEvent.Usage -> event.turnId
            is InferenceEvent.TextDelta -> event.turnId
            is InferenceEvent.Completed -> event.turnId
            is InferenceEvent.Failed -> event.turnId
        }
        if (eventTurnId != activeTurnId) return
        when (event) {
            is InferenceEvent.Usage -> {
                if (event.outputTokens >= 0 && event.decodeNanos > 0) _state.update {
                    it.copy(metrics = it.metrics.copy(outputTokens = event.outputTokens, decodeNanos = event.decodeNanos))
                }
            }
            is InferenceEvent.TextDelta -> _state.update { state ->
                state.copy(metrics = state.metrics.copy(firstTextMs = state.metrics.firstTextMs ?: ((System.nanoTime() - startedAt) / 1_000_000)), turns = state.turns.map { turn ->
                    if (turn is ProbeTurn.Assistant && turn.id == event.turnId) turn.copy(text = turn.text + event.value) else turn
                })
            }
            is InferenceEvent.Completed -> {
                completedTurns += event.turnId
                activeTurnId = null
                _state.update { it.copy(isGenerating = false, error = if (event.outputLimitReached) "Response reached the 128-token experiment limit." else null, trace = (it.trace + "Stream completed" + "Tokenizer usage: ${if (it.metrics.outputTokens == null) "unavailable" else "reported by runtime"}").takeLast(32)) }
            }
            is InferenceEvent.Failed -> {
                activeTurnId = null
                _state.update { it.copy(isGenerating = false, error = event.reason) }
            }
        }
    }
}

data class ProbeUiState(
    val draft: String = "",
    val photoAttached: Boolean = false,
    val audioAttached: Boolean = false,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val turns: List<ProbeTurn> = emptyList(),
    val metrics: GenerationMetrics = GenerationMetrics(),
    val trace: List<String> = emptyList(),
    val engineName: String = "deterministic-preview",
    val realInference: Boolean = false,
)

data class GenerationMetrics(val outputTokens: Int? = null, val decodeNanos: Long? = null, val firstTextMs: Long? = null) {
    val tokensPerSecond: Double? get() = if (outputTokens != null && decodeNanos != null && decodeNanos > 0) outputTokens * 1_000_000_000.0 / decodeNanos else null
}

sealed interface ProbeTurn {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
        val includesPhoto: Boolean,
        val includesAudio: Boolean,
    ) : ProbeTurn

    data class Assistant(override val id: Long, val text: String) : ProbeTurn
}
