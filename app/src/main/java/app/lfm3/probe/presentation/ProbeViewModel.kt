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
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.media.VoiceRecorder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.lfm3.probe.history.*
import android.content.ContentResolver
import app.lfm3.probe.online.*

class ProbeViewModel(
    private val engine: InferenceEngine = DeterministicProbeEngine(),
    private val mediaStore: MediaStore? = null,
    private val repository: ConversationRepository? = null,
    private val resolver: ContentResolver? = null,
    private val online: OnlineTools? = null,
    private val loadedContext: () -> Int = { 0 },
) : ViewModel() {
    private val _state = MutableStateFlow(ProbeUiState(isHistoryBusy = repository != null))
    val state: StateFlow<ProbeUiState> = _state.asStateFlow()
    private var generation: Job? = null
    private var nextTurnId = 1L
    private var activeTurnId: Long? = null
    private var startedAt = 0L
    private val completedTurns = mutableSetOf<Long>()
    var outputLimit: Int = 128
    private val recorder = mediaStore?.let { VoiceRecorder(it) }
    private var recording: Job? = null
    private var importing: Job? = null
    private var chatEpoch = 0L
    private val persistence = Mutex()
    private var historyJob: Job? = null
    private var searchJob: Job? = null
    private var draftJob: Job? = null
    private var cancellationSave: Job? = null
    private var persistedId: String? = null
    private val archive = if (repository != null && mediaStore != null) ConversationArchive(repository, mediaStore) else null

    init {
        if (repository != null) historyJob = viewModelScope.launch {
            try {
                repository.recoverInterrupted()
                val retained = repository.references()
                withContext(Dispatchers.IO) {
                    mediaStore?.prune(retained)
                    mediaStore?.directory?.parentFile?.listFiles()?.filter { it.isDirectory && it.name.startsWith("restore-") }?.forEach { it.deleteRecursively() }
                }
                refreshLibrary()
                _state.value.library.firstOrNull()?.let { restore(it.id) }
            } catch (_: Exception) { reportError("Unable to open saved history. Your files have not been erased.") }
            finally { _state.update { it.copy(isHistoryBusy = false) } }
        }
    }

    private suspend fun checkpoint(force: Boolean = false) {
        val store = repository ?: return
        persistence.withLock {
            val state = _state.value
            if (state.isHistoryBusy && !force) return@withLock
            if (persistedId == state.conversationId || state.turns.isNotEmpty() || state.draft.isNotBlank() || state.photoReference != null || state.audioReference != null) {
                val draftMedia = listOfNotNull(state.photoReference?.let { RequestPart.Image(it) },
                    state.audioReference?.let { RequestPart.Audio(it, state.audioDurationMs) })
                store.save(SavedConversation(state.conversationId, state.conversationTitle, state.turns, state.realInference,
                    state.engineName, state.draft, state.speakReplies, draftMedia))
                persistedId = state.conversationId
            }
        }
    }
    private suspend fun refreshLibrary(append: Boolean = false) {
        val state = _state.value
        val page = repository?.list(state.historyQuery, if (append) state.library.size else 0).orEmpty()
        _state.update { it.copy(library = (if (append) it.library else emptyList()) + page, hasMoreConversations = page.size == 30) }
    }
    private suspend fun restore(id: String, limit: Int = 40) {
        val saved = checkNotNull(repository?.load(id, limit)) { "Conversation no longer exists." }
        completedTurns.clear()
        completedTurns.addAll(saved.turns.filterIsInstance<ProbeTurn.Assistant>().filter { it.status == ReplyStatus.COMPLETE }.map { it.id })
        nextTurnId = (saved.turns.maxOfOrNull { it.id } ?: 0) + 1
        val count = repository.count(id)
        persistedId = id
        val image = saved.draftMedia.filterIsInstance<RequestPart.Image>().singleOrNull()
        val audio = saved.draftMedia.filterIsInstance<RequestPart.Audio>().singleOrNull()
        _state.update { it.copy(conversationId = id, conversationTitle = saved.title, turns = saved.turns, draft = saved.draft,
            photoReference = image?.privateReference, photoAttached = image != null, audioReference = audio?.privateReference,
            audioAttached = audio != null, audioDurationMs = audio?.durationMs ?: 0,
            realInference = saved.real, engineName = saved.engine, speakReplies = saved.speak, totalTurns = count,
            error = if (saved.turns.any { turn -> turn is ProbeTurn.Assistant && turn.status == ReplyStatus.INTERRUPTED }) "An earlier response was interrupted. It was not replayed." else null,
            metrics = GenerationMetrics(), trace = emptyList()) }
    }
    fun searchHistory(query: String) {
        _state.update { it.copy(historyQuery = query.take(200)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(250); try { refreshLibrary() } catch (_: Exception) { reportError("Unable to search history.") } }
    }
    fun refreshHistory() = historyAction { checkpoint(true); refreshLibrary() }
    fun moreConversations() = historyAction { refreshLibrary(true) }
    fun olderMessages() = historyAction { checkpoint(true); restore(_state.value.conversationId, (_state.value.turns.size / 2 + 40).coerceAtMost(500)) }
    fun openConversation(id: String) = historyAction(stop = true) { checkpoint(true); restore(id) }
    fun renameConversation(id: String, title: String) = historyAction {
        checkpoint(true); repository?.rename(id, title)
        if (_state.value.conversationId == id) _state.update { it.copy(conversationTitle = title.trim()) }
        refreshLibrary()
    }
    fun deleteConversation(id: String) = historyAction(stop = true) {
        checkpoint(true); repository?.delete(id)
        if (_state.value.conversationId == id) resetChat()
        // Drafts/imports remain leased until their owner is stopped and the operation completes.
        val keep = repository?.references().orEmpty() + listOfNotNull(_state.value.photoReference, _state.value.audioReference)
        withContext(Dispatchers.IO) { mediaStore?.prune(keep) }
        refreshLibrary()
    }
    fun exportConversation(id: String, uri: Uri) = historyAction {
        checkpoint(true)
        withContext(Dispatchers.IO) { checkNotNull(resolver?.openOutputStream(uri, "wt")).use { checkNotNull(archive).export(id, it) } }
        reportError("Conversation exported. The archive includes its text and attachments; store it privately.")
    }
    fun restoreConversation(uri: Uri) = historyAction(stop = true) {
        checkpoint(true)
        val id = withContext(Dispatchers.IO) { checkNotNull(resolver?.openInputStream(uri)).use { checkNotNull(archive).restore(it) } }
        restore(id); refreshLibrary()
    }
    private fun historyAction(stop: Boolean = false, block: suspend () -> Unit) {
        if (historyJob?.isActive == true || _state.value.isImporting) return
        val precedingGeneration = generation
        if (stop) { ++chatEpoch; cancel(); stopRecording(true) }
        else if (_state.value.isGenerating || _state.value.isRecording) return
        _state.update { it.copy(isHistoryBusy = true) }
        historyJob = viewModelScope.launch {
            try { if (stop) { precedingGeneration?.join(); cancellationSave?.join(); recording?.join() }; block() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { reportError("History operation failed. Check storage or the archive and retry; existing conversations were retained.") }
            finally { _state.update { it.copy(isHistoryBusy = false) } }
        }
    }

    fun reportError(message: String) = _state.update { it.copy(error = message) }
    /** User-confirmed import only. Save the previous chat first and never call submit here. */
    fun acceptSharedInput(id: String, text: String, image: Uri?) {
        if (_state.value.isHistoryBusy || _state.value.isImporting || _state.value.isRecording || _state.value.isGenerating) return
        if (text.length > 8000 || (text.isBlank() && image == null) || (image != null && image.scheme != "content")) { reportError("Shared input is empty, too long or unsupported."); return }
        _state.update { it.copy(error = null, shareImportError = null) }
        historyAction(stop = true) {
            try {
                checkpoint(true)
                val reference = image?.let { checkNotNull(mediaStore).importPhoto(it) }
                resetChat()
                _state.update { it.copy(draft = text, photoReference = reference, photoAttached = reference != null,
                    conversationTitle = text.trim().take(80).ifBlank { "Shared photo" }) }
                checkpoint(true)
                refreshLibrary()
                _state.update { it.copy(acceptedShareId = id) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(shareImportError = "Could not import or save this share. Existing conversations were retained. Check storage/access and share it again.") } }
        }
    }
    fun consumeAcceptedShare(id: String) { _state.update { if (it.acceptedShareId == id) it.copy(acceptedShareId = null) else it } }
    fun reviewLastQuestion() {
        val state = _state.value
        if (state.isGenerating || state.isHistoryBusy || state.isRecording || state.isImporting) return
        val user = state.turns.filterIsInstance<ProbeTurn.User>().lastOrNull() ?: return
        val images = user.media.filterIsInstance<RequestPart.Image>()
        val audio = user.media.filterIsInstance<RequestPart.Audio>()
        if (images.size > 1 || audio.size > 1) { reportError("This question has multiple attachments. Start a new question with the desired media."); return }
        _state.update { it.copy(draft = user.text, photoAttached = images.isNotEmpty(), photoReference = images.firstOrNull()?.privateReference,
            audioAttached = audio.isNotEmpty(), audioReference = audio.firstOrNull()?.privateReference, audioDurationMs = audio.firstOrNull()?.durationMs ?: 0,
            error = "Question restored to the composer. Review it, then press Send when ready.") }
        saveDraftSoon()
    }
    fun importPhoto(uri: Uri) {
        if (_state.value.isGenerating || _state.value.isImporting || _state.value.isHistoryBusy) return
        _state.update { it.copy(isImporting = true, error = null) }
        val epoch = chatEpoch
        importing = viewModelScope.launch {
            try {
                val reference = checkNotNull(mediaStore).importPhoto(uri)
                if (epoch == chatEpoch) { _state.update { it.copy(photoAttached = true, photoReference = reference) }; checkpoint() }
            } catch (_: CancellationException) { /* A new chat invalidates the import. */ }
            catch (_: Exception) { if (epoch == chatEpoch) reportError("Unable to import photo. Use a supported image under 20 MB.") }
            finally { if (epoch == chatEpoch) _state.update { it.copy(isImporting = false) } }
        }
    }
    fun clearPhoto() { _state.update { it.copy(photoAttached = false, photoReference = null) }; saveDraftSoon() }
    fun clearAudio() { _state.update { it.copy(audioAttached = false, audioReference = null, audioDurationMs = 0) }; saveDraftSoon() }
    fun toggleSpeech() { _state.update { it.copy(speakReplies = !it.speakReplies) }; saveDraftSoon() }
    fun startRecording() {
        if (_state.value.isRecording || _state.value.isGenerating || _state.value.isHistoryBusy || recording?.isActive == true) return
        val capture = recorder ?: return
        capture.prepare()
        _state.update { it.copy(isRecording = true, error = null) }
        recording = viewModelScope.launch {
            try {
                val (reference, duration) = capture.record()
                _state.update { it.copy(audioAttached = true, audioReference = reference, audioDurationMs = duration, speakReplies = true) }
                checkpoint()
            } catch (_: CancellationException) { /* Background/cancel discards the capture. */ }
            catch (_: Exception) { reportError("Microphone capture failed or was too short. Check permission and retry.") }
            finally { _state.update { it.copy(isRecording = false) } }
        }
    }
    fun stopRecording(discard: Boolean = false) { recorder?.stop(); if (discard) recording?.cancel() }
    override fun onCleared() { online?.cancel(); engine.cancel(); stopRecording(true); super.onCleared() }
    fun approveTool(id: Long, allow: Boolean) { online?.approve(id, allow) }

    fun newChat() {
        if (repository != null) { historyAction(stop = true) { checkpoint(true); resetChat(); refreshLibrary() }; return }
        resetChat()
    }
    private fun resetChat() {
        persistedId = null
        ++chatEpoch
        importing?.cancel()
        cancel()
        stopRecording(true)
        completedTurns.clear()
        _state.value = ProbeUiState(isHistoryBusy = _state.value.isHistoryBusy, engineName = engine.descriptor.id,
            realInference = engine.descriptor.kind != app.lfm3.probe.inference.EngineKind.DETERMINISTIC_PROBE)
    }

    fun updateDraft(value: String) {
        if (_state.value.isHistoryBusy) return
        _state.update { it.copy(draft = value.take(8_000)) }
        saveDraftSoon()
    }
    private fun saveDraftSoon() {
        if (repository != null) {
            draftJob?.cancel()
            draftJob = viewModelScope.launch { delay(500); runCatching { checkpoint() }.onFailure { reportError("Unable to save draft. Check free storage.") } }
        }
    }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.isGenerating || snapshot.isRecording || snapshot.isImporting || snapshot.isHistoryBusy || (snapshot.draft.isBlank() && !snapshot.photoAttached && !snapshot.audioAttached)) return
        if (snapshot.totalTurns >= 500) { reportError("This conversation has reached 500 turns. Start a new chat; your history remains saved."); return }
        if (snapshot.turns.isNotEmpty() && (snapshot.realInference != (engine.descriptor.kind != app.lfm3.probe.inference.EngineKind.DETERMINISTIC_PROBE) || (snapshot.realInference && snapshot.engineName != engine.descriptor.id))) {
            reportError("Load this conversation's model, or start a new chat to use a different model/preview."); return
        }
        val turnId = nextTurnId++
        val epoch = chatEpoch
        startedAt = System.nanoTime()
        val requestParts = buildList {
            if (snapshot.draft.isNotBlank()) add(RequestPart.Text(snapshot.draft.trim()))
            snapshot.photoReference?.let { add(RequestPart.Image(it)) }
            snapshot.audioReference?.let { add(RequestPart.Audio(it, snapshot.audioDurationMs)) }
        }
        _state.update {
            it.copy(
                turns = it.turns + ProbeTurn.User(turnId, snapshot.draft, snapshot.photoAttached, snapshot.audioAttached, requestParts.filterNot { part -> part is RequestPart.Text }) + ProbeTurn.Assistant(turnId, ""),
                totalTurns = it.totalTurns + 1,
                conversationTitle = if (it.turns.isEmpty() && it.conversationTitle == "New conversation") snapshot.draft.trim().take(80).ifBlank { "Photo / voice conversation" } else it.conversationTitle,
                draft = "",
                photoAttached = false,
                audioAttached = false,
                photoReference = null, audioReference = null, audioDurationMs = 0,
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
            var snapshots: Job? = null
            try {
                // Commit user input before native execution; checkpoint partial output at bounded intervals.
                checkpoint()
                snapshots = launch { while (true) {
                    delay(500)
                    try { checkpoint() }
                    catch (_: Exception) {
                        withContext(NonCancellable + Dispatchers.Main.immediate) { cancel(); reportError("History could not be saved. The response was stopped; check free storage before retrying.") }
                        break
                    }
                } }
                val full = if (repository != null && snapshot.totalTurns > snapshot.turns.size / 2) repository.load(snapshot.conversationId, 500)?.turns.orEmpty().filter { it.id != turnId } else snapshot.turns
                val complete = full.filterIsInstance<ProbeTurn.Assistant>().filter { it.status == ReplyStatus.COMPLETE }.map { it.id }.toSet()
                val history = full.filter { it.id in complete }.map {
                    when (it) {
                        is ProbeTurn.User -> ChatMessage("user", it.text, it.media)
                        is ProbeTurn.Assistant -> ChatMessage("assistant", it.text)
                    }
                }
                val request = InferenceRequest(turnId, requestParts, history, generationTicket, outputLimit, snapshot.speakReplies)
                val emit: suspend (InferenceEvent) -> Unit = { event -> withContext(Dispatchers.Main.immediate) {
                        if (epoch == chatEpoch && _state.value.conversationId == snapshot.conversationId) handleEvent(event)
                    } }
                if (online == null) engine.generate(request, emit)
                else online.generate(engine, request, loadedContext(), { progress -> withContext(Dispatchers.Main.immediate) {
                    if (epoch == chatEpoch && activeTurnId == turnId && _state.value.conversationId == snapshot.conversationId) _state.update { state ->
                        state.copy(toolProgress = progress, turns = if (progress.receipt != null) state.turns.map {
                            if (it is ProbeTurn.Assistant && it.id == turnId) it.copy(receipt = progress.receipt) else it
                        } else state.turns, trace = if (progress.stage.isNotEmpty()) (state.trace + progress.stage).takeLast(32) else state.trace)
                    }
                } }, emit)
            } catch (_: CancellationException) {
                // Cancellation is expected. A future native adapter must stop native work here too.
            } catch (e: Exception) {
                if (activeTurnId == turnId) _state.update { it.copy(turns = markReply(it.turns, turnId,
                    if (e is app.lfm3.probe.inference.RuntimeDisconnectedException) ReplyStatus.INTERRUPTED else ReplyStatus.FAILED),
                    error = if (e is IllegalStateException) e.message else "The response was interrupted. Please retry.") }
            } finally {
                snapshots?.cancel()
                if (activeTurnId == turnId) {
                    activeTurnId = null
                    _state.update { it.copy(isGenerating = false, toolProgress = ToolProgress(), turns = markReply(it.turns, turnId, ReplyStatus.INTERRUPTED, onlyStreaming = true)) }
                }
                withContext(NonCancellable) {
                    try { checkpoint(); refreshLibrary() }
                    catch (_: Exception) { withContext(Dispatchers.Main.immediate) { reportError("Latest history changes could not be saved. Check free storage before closing Tavi.") } }
                }
            }
        }
    }

    fun cancel() {
        online?.cancel()
        engine.cancel()
        val interrupted = activeTurnId
        activeTurnId = null // A late callback from a cancelled engine cannot alter a later turn.
        generation?.cancel()
        _state.update { it.copy(isGenerating = false, toolProgress = ToolProgress(), turns = if (interrupted != null) markReply(it.turns, interrupted, ReplyStatus.INTERRUPTED) else it.turns,
            error = if (interrupted != null) "Response stopped. Partial reply saved." else it.error, trace = (it.trace + "Cancellation acknowledged").takeLast(32)) }
        if (repository != null && interrupted != null) cancellationSave = viewModelScope.launch { runCatching { checkpoint() } }
    }

    private fun handleEvent(event: InferenceEvent) {
        val eventTurnId = when (event) {
            is InferenceEvent.Usage -> event.turnId
            is InferenceEvent.TextDelta -> event.turnId
            is InferenceEvent.Completed -> event.turnId
            is InferenceEvent.Failed -> event.turnId
        }
        if (eventTurnId != activeTurnId) return
        if (event is InferenceEvent.TextDelta && (_state.value.turns.filterIsInstance<ProbeTurn.Assistant>().lastOrNull()?.text?.length ?: 0) + event.value.length > 32_768) {
            cancel(); reportError("Reply exceeded the saved-message limit and was stopped. Partial output was retained."); return
        }
        when (event) {
            is InferenceEvent.Usage -> {
                if (event.outputTokens >= 0 && event.decodeNanos > 0) _state.update {
                    it.copy(metrics = it.metrics.copy(outputTokens = event.outputTokens, decodeNanos = event.decodeNanos,
                        audioSamples = event.audioSamples.takeIf { count -> count > 0 }, audioRate = event.audioRate.takeIf { rate -> rate > 0 }, firstAudioMs = event.firstAudioMs,
                        prefillNanos = event.prefillNanos.takeIf { n -> n > 0 }, optimizedCpuBuffer = event.optimizedCpuBuffer))
                }
            }
            is InferenceEvent.TextDelta -> _state.update { state ->
                state.copy(metrics = state.metrics.copy(firstTextMs = state.metrics.firstTextMs ?: ((System.nanoTime() - startedAt) / 1_000_000)), turns = state.turns.map { turn ->
                    if (turn is ProbeTurn.Assistant && turn.id == event.turnId) turn.copy(text = (turn.text + event.value).take(32_768)) else turn
                })
            }
            is InferenceEvent.Completed -> {
                completedTurns += event.turnId
                activeTurnId = null
                _state.update { it.copy(isGenerating = false, toolProgress = ToolProgress(), turns = markReply(it.turns, event.turnId, ReplyStatus.COMPLETE), error = if (event.outputLimitReached) "Response reached the configured output-token limit." else null, trace = (it.trace + "Stream completed" + "Tokenizer usage: ${if (it.metrics.outputTokens == null) "unavailable" else "reported by runtime"}").takeLast(32)) }
            }
            is InferenceEvent.Failed -> {
                activeTurnId = null
                _state.update { it.copy(isGenerating = false, turns = markReply(it.turns, event.turnId, ReplyStatus.FAILED), error = event.reason) }
            }
        }
    }
    private fun markReply(turns: List<ProbeTurn>, id: Long, status: ReplyStatus, onlyStreaming: Boolean = false) = turns.map {
        if (it is ProbeTurn.Assistant && it.id == id && (!onlyStreaming || it.status == ReplyStatus.STREAMING)) it.copy(status = status) else it
    }
}

data class ProbeUiState(
    val conversationId: String = java.util.UUID.randomUUID().toString(),
    val conversationTitle: String = "New conversation",
    val library: List<ConversationSummary> = emptyList(),
    val historyQuery: String = "",
    val hasMoreConversations: Boolean = false,
    val isHistoryBusy: Boolean = false,
    val totalTurns: Int = 0,
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
    val photoReference: String? = null,
    val audioReference: String? = null,
    val audioDurationMs: Long = 0,
    val isRecording: Boolean = false,
    val isImporting: Boolean = false,
    val speakReplies: Boolean = false,
    val toolProgress: ToolProgress = ToolProgress(),
    val acceptedShareId: String? = null,
    val shareImportError: String? = null,
)

data class GenerationMetrics(val outputTokens: Int? = null, val decodeNanos: Long? = null, val firstTextMs: Long? = null,
                             val audioSamples: Long? = null, val audioRate: Int? = null, val firstAudioMs: Long? = null,
                             val prefillNanos: Long? = null, val optimizedCpuBuffer: Boolean = false) {
    val tokensPerSecond: Double? get() = if (outputTokens != null && decodeNanos != null && decodeNanos > 0) outputTokens * 1_000_000_000.0 / decodeNanos else null
}

enum class ReplyStatus { STREAMING, COMPLETE, INTERRUPTED, FAILED }

sealed interface ProbeTurn {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
        val includesPhoto: Boolean,
        val includesAudio: Boolean,
        val media: List<RequestPart> = emptyList(),
    ) : ProbeTurn

    data class Assistant(override val id: Long, val text: String, val status: ReplyStatus = ReplyStatus.STREAMING,
                         val receipt: ToolReceipt? = null) : ProbeTurn
}
