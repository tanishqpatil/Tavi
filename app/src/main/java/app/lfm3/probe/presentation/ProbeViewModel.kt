package app.lfm3.probe.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lfm3.probe.inference.DeterministicProbeEngine
import app.lfm3.probe.inference.InferenceEngine
import app.lfm3.probe.inference.InferenceEvent
import app.lfm3.probe.inference.InferenceRequest
import app.lfm3.probe.inference.RequestPart
import kotlinx.coroutines.Job
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

    fun updateDraft(value: String) = _state.update { it.copy(draft = value) }

    fun togglePhoto() = _state.update { it.copy(photoAttached = !it.photoAttached) }

    fun toggleAudio() = _state.update { it.copy(audioAttached = !it.audioAttached) }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.isGenerating || (snapshot.draft.isBlank() && !snapshot.photoAttached && !snapshot.audioAttached)) return
        val turnId = nextTurnId++
        val requestParts = buildList {
            if (snapshot.draft.isNotBlank()) add(RequestPart.Text(snapshot.draft.trim()))
            if (snapshot.photoAttached) add(RequestPart.Image("fixture-photo"))
            if (snapshot.audioAttached) add(RequestPart.Audio("fixture-audio", durationMs = 5_000))
        }
        _state.update {
            it.copy(
                turns = it.turns + ProbeTurn.User(turnId, snapshot.draft, snapshot.photoAttached, snapshot.audioAttached) + ProbeTurn.Assistant(turnId, ""),
                draft = "",
                photoAttached = false,
                audioAttached = false,
                isGenerating = true,
                error = null,
            )
        }
        activeTurnId = turnId
        generation = viewModelScope.launch {
            try {
                engine.generate(InferenceRequest(turnId, requestParts)) { event -> handleEvent(event) }
            } catch (_: CancellationException) {
                // Cancellation is expected. A future native adapter must stop native work here too.
            } finally {
                if (activeTurnId == turnId) {
                    activeTurnId = null
                    _state.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    fun cancel() {
        activeTurnId = null // A late callback from a cancelled engine cannot alter a later turn.
        generation?.cancel()
        generation = null
        _state.update { it.copy(isGenerating = false, error = "Probe generation cancelled.") }
    }

    private fun handleEvent(event: InferenceEvent) {
        val eventTurnId = when (event) {
            is InferenceEvent.TextDelta -> event.turnId
            is InferenceEvent.Completed -> event.turnId
            is InferenceEvent.Failed -> event.turnId
        }
        if (eventTurnId != activeTurnId) return
        when (event) {
            is InferenceEvent.TextDelta -> _state.update { state ->
                state.copy(turns = state.turns.map { turn ->
                    if (turn is ProbeTurn.Assistant && turn.id == event.turnId) turn.copy(text = turn.text + event.value) else turn
                })
            }
            is InferenceEvent.Completed -> {
                activeTurnId = null
                _state.update { it.copy(isGenerating = false) }
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
)

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
