package app.lfm3.probe.models

import android.content.Context
import app.lfm3.probe.inference.*
import app.lfm3.probe.diagnostics.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class RuntimePhase { CHECKING, AVAILABLE, DOWNLOADING, INSTALLED, LOADING, LOADED, ERROR, UNAVAILABLE }
data class RuntimeState(
    val phase: RuntimePhase = RuntimePhase.CHECKING,
    val nativeInfo: String? = null,
    val message: String = "Checking native runtime…",
    val downloaded: Long = 0,
    val selectedId: String = "lfm3-text",
    val installedIds: Set<String> = emptySet(),
    val loadedId: String? = null,
    val loadedContext: Int = 0,
) {
    val installed get() = selectedId in installedIds
    val loaded get() = loadedId != null
    val selected get() = ModelCatalog.get(selectedId)
    val busy get() = phase in setOf(RuntimePhase.CHECKING, RuntimePhase.DOWNLOADING, RuntimePhase.LOADING)
}

class RuntimeManager(context: Context) {
    private val preferences = app.lfm3.probe.ui.TaviSettings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = AppLog(context)
    private val installers = ModelCatalog.packs.associate { it.id to PackInstaller(File(context.noBackupFilesDir, "models"), log::record, it) }
    private val preview = DeterministicProbeEngine()
    private val _state = MutableStateFlow(RuntimeState())
    val state = _state.asStateFlow()
    @Volatile private var nativeRequested = false
    private val native = RemoteLlamaEngine(context) {
        log.record("RUNTIME_PROCESS_DISCONNECTED")
        _state.value = _state.value.copy(phase = RuntimePhase.ERROR, loadedId = null, loadedContext = 0,
            message = "Runtime process stopped. Your history is safe. Tap Load model to recover; requests are not replayed.")
    }
    private var task: Job? = null
    val engine = object : InferenceEngine {
        override val descriptor get() = if (native.loaded || nativeRequested) native.descriptor else preview.descriptor
        override fun prepare(): Long = if (native.loaded || nativeRequested) native.prepare() else 0
        override fun cancel() { native.cancel() }
        override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) {
            if (native.loaded) native.generate(request, emit)
            else if (nativeRequested) throw RuntimeDisconnectedException()
            else preview.generate(request, emit)
        }
    }
    init {
        task = scope.launch {
            try {
                val info = native.info()
                log.record("NATIVE_RUNTIME_INITIALIZED")
                val valid = installers.filterValues { it.verifyInstalled() }.keys
                _state.value = RuntimeState(if ("lfm3-text" in valid) RuntimePhase.INSTALLED else RuntimePhase.AVAILABLE,
                    info, "CPU runtime initialized. Select an installed pack to load.", installedIds = valid)
            } catch (_: LinkageError) { _state.value = RuntimeState(RuntimePhase.UNAVAILABLE, message = "ARM64 native runtime unavailable on this device.") }
            catch (_: Exception) { _state.value = RuntimeState(RuntimePhase.UNAVAILABLE, message = "Runtime initialization failed.") }
        }
    }
    fun select(id: String) {
        if (task?.isActive == true || native.loaded || native.generating) return
        ModelCatalog.get(id)
        _state.value = _state.value.copy(selectedId = id, phase = if (id in _state.value.installedIds) RuntimePhase.INSTALLED else RuntimePhase.AVAILABLE,
            downloaded = 0, message = "${ModelCatalog.get(id).title} selected.")
    }
    fun download() {
        if (task?.isActive == true || native.loaded || _state.value.nativeInfo == null || _state.value.installed) return
        val id = _state.value.selectedId
        val installer = installers.getValue(id)
        task = scope.launch {
            try {
                _state.value = _state.value.copy(phase = RuntimePhase.DOWNLOADING, message = "Downloading and verifying. Keep Tavi open.")
                installer.install { bytes, _ -> _state.value = _state.value.copy(downloaded = bytes) }
                _state.value = _state.value.copy(phase = RuntimePhase.INSTALLED, installedIds = _state.value.installedIds + id, message = "All pack components verified. Ready to load.")
            } catch (_: CancellationException) { _state.value = _state.value.copy(phase = RuntimePhase.AVAILABLE, message = "Download paused. Tap Download to resume.") }
            catch (e: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, message = safeError(e, "Download failed. Check connectivity and storage, then retry.")) }
        }
    }
    fun pauseDownload() {
        if (_state.value.phase == RuntimePhase.DOWNLOADING) { task?.cancel(); installers.getValue(_state.value.selectedId).interrupt() }
    }
    fun load() {
        if (task?.isActive == true || !_state.value.installed || native.loaded || native.generating) return
        val pack = _state.value.selected
        nativeRequested = true
        val installer = installers.getValue(pack.id)
        task = scope.launch {
            try {
                _state.value = _state.value.copy(phase = RuntimePhase.LOADING, message = "Verifying files and allocating model context…")
                check(installer.verifyInstalled()) { "Installed pack failed verification. Remove and download again." }
                val limits = preferences.limits
                native.load(pack, limits.context)
                log.record("LFM3_MODEL_LOADED")
                _state.value = _state.value.copy(phase = RuntimePhase.LOADED, loadedId = pack.id, loadedContext = limits.context,
                    message = "${pack.title} · CPU · ${limits.context}-token context${if (pack.experimental) " · Experimental" else ""}")
            } catch (e: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, loadedId = null, message = safeError(e, "Model load failed.")) }
        }
    }
    fun unload() {
        if (task?.isActive == true || native.generating) return
        task = scope.launch {
            try {
                native.unload(); log.record("LFM3_MODEL_UNLOADED")
                nativeRequested = false
                _state.value = _state.value.copy(phase = RuntimePhase.INSTALLED, loadedId = null, loadedContext = 0, message = "Model unloaded. Native resources released.")
            } catch (e: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, message = safeError(e, "Unable to unload yet. Stop the response and retry.")) }
        }
    }
    fun remove() {
        if (task?.isActive == true || native.loaded || native.generating) return
        val id = _state.value.selectedId
        task = scope.launch {
            try {
                installers.getValue(id).remove()
                _state.value = _state.value.copy(phase = RuntimePhase.AVAILABLE, installedIds = _state.value.installedIds - id, downloaded = 0, message = "Model files removed.")
            } catch (_: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, message = "Unable to remove model files.") }
        }
    }
    private fun safeError(error: Exception, fallback: String): String = when (error) {
        is IllegalStateException -> error.message?.takeIf { !it.contains('/') && it.length <= 200 } ?: fallback
        else -> fallback
    }
}
