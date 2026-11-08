package app.lfm3.probe.models

import android.content.Context
import app.lfm3.probe.inference.*
import app.lfm3.probe.diagnostics.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val loadedCpu: CpuOptions? = null,
    val loadedBackend: RuntimeBackend? = null,
    val unmeteredDownloads: Boolean = true,
    val downloadPausePending: Boolean = false,
) {
    val installed get() = selectedId in installedIds
    val loaded get() = loadedId != null
    val selected get() = ModelCatalog.get(selectedId)
    val busy get() = phase in setOf(RuntimePhase.CHECKING, RuntimePhase.DOWNLOADING, RuntimePhase.LOADING)
}

class RuntimeManager(context: Context) {
    private val preferences = app.lfm3.probe.ui.TaviSettings(context)
    private val downloads = (context.applicationContext as app.lfm3.probe.TaviApplication).downloads
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = AppLog(context)
    private val installers = ModelCatalog.packs.associate { it.id to PackInstaller(File(context.noBackupFilesDir, "models"), log::record, it) }
    private val preview = DeterministicProbeEngine()
    private val _state = MutableStateFlow(RuntimeState(unmeteredDownloads = preferences.unmeteredDownloads))
    val state = _state.asStateFlow()
    @Volatile private var nativeRequested = false
    private val native = RemoteLlamaEngine(context) {
        log.record("RUNTIME_PROCESS_DISCONNECTED")
        _state.update { it.copy(phase = if (downloads.state.value.active) RuntimePhase.DOWNLOADING else RuntimePhase.ERROR, loadedId = null, loadedContext = 0, loadedCpu = null, loadedBackend = null,
            message = if (downloads.state.value.active) downloads.state.value.message else "Runtime process stopped. Your history is safe. Tap Load model to recover; requests are not replayed.") }
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
                    info, "CPU runtime initialized. Select an installed pack to load.", installedIds = valid, unmeteredDownloads = preferences.unmeteredDownloads)
            } catch (_: LinkageError) { _state.value = RuntimeState(RuntimePhase.UNAVAILABLE, message = "ARM64 native runtime unavailable on this device.") }
            catch (_: Exception) { _state.value = RuntimeState(RuntimePhase.UNAVAILABLE, message = "Runtime initialization failed.") }
            scope.launch { downloads.state.collect { applyDownload(it) } }
        }
    }
    fun select(id: String) {
        if (task?.isActive == true || native.loaded || native.generating || downloads.state.value.active) return
        ModelCatalog.get(id)
        _state.value = _state.value.copy(selectedId = id, phase = if (id in _state.value.installedIds) RuntimePhase.INSTALLED else RuntimePhase.AVAILABLE,
            downloaded = 0, message = "${ModelCatalog.get(id).title} selected.")
    }
    fun download() {
        if (task?.isActive == true || native.loaded || native.generating || downloads.state.value.active || _state.value.nativeInfo == null || _state.value.installed) return
        val id = _state.value.selectedId
        task = scope.launch {
            try {
                _state.update { it.copy(phase = RuntimePhase.DOWNLOADING, message = "Scheduling a background transfer…") }
                downloads.enqueue(ModelCatalog.get(id), _state.value.unmeteredDownloads)
            } catch (_: CancellationException) { /* App shutdown leaves the persistent scheduler in control. */ }
            catch (e: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, message = safeError(e, "Download failed. Check connectivity and storage, then retry.")) }
        }
    }
    fun pauseDownload() {
        downloads.pause()
    }
    fun usePreview() {
        if (native.loaded || native.generating || task?.isActive == true) return
        nativeRequested = false
    }
    fun unmeteredDownloads(value: Boolean) {
        if (downloads.state.value.active) return
        preferences.unmeteredDownloads = value
        _state.update { it.copy(unmeteredDownloads = value) }
    }
    private suspend fun applyDownload(record: DownloadRecord) {
        if (record.status == DownloadStatus.IDLE || record.packId !in installers) return
        if (_state.value.loaded || _state.value.phase == RuntimePhase.LOADING) return
        val verified = record.status == DownloadStatus.COMPLETE &&
            (record.packId in _state.value.installedIds || installers.getValue(record.packId).verifyInstalled())
        _state.update { previous -> previous.copy(selectedId = record.packId, downloaded = record.bytes,
            installedIds = if (verified) previous.installedIds + record.packId else previous.installedIds,
            phase = when {
                record.active -> RuntimePhase.DOWNLOADING
                record.status == DownloadStatus.FAILED -> RuntimePhase.ERROR
                verified || record.packId in previous.installedIds -> RuntimePhase.INSTALLED
                else -> RuntimePhase.AVAILABLE
            }, message = if (record.status == DownloadStatus.COMPLETE && !verified) "Stored pack is missing or damaged. Remove its files and download again." else record.message,
            downloadPausePending = record.status == DownloadStatus.PAUSING) }
    }
    fun load() {
        if (task?.isActive == true || downloads.state.value.active || !_state.value.installed || native.loaded || native.generating) return
        val pack = _state.value.selected
        nativeRequested = true
        val installer = installers.getValue(pack.id)
        task = scope.launch {
            try {
                _state.value = _state.value.copy(phase = RuntimePhase.LOADING, message = "Verifying files and allocating model context…")
                check(installer.verifyInstalled()) { "Installed pack failed verification. Remove and download again." }
                val limits = preferences.limits
                val cpu = preferences.cpu
                val backend = preferences.backend
                native.load(pack, limits.context, cpu, backend)
                log.record("LFM3_MODEL_LOADED")
                _state.value = _state.value.copy(phase = RuntimePhase.LOADED, loadedId = pack.id, loadedContext = limits.context, loadedCpu = cpu, loadedBackend = backend,
                    message = "${pack.title} · ${backend.label} · ${limits.context}-token context${if (pack.experimental) " · Experimental" else ""}")
            } catch (e: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, loadedId = null, loadedCpu = null, loadedBackend = null, message = safeError(e, "Model load failed.")) }
        }
    }
    fun unload() {
        if (task?.isActive == true || native.generating) return
        task = scope.launch {
            try {
                native.unload(); log.record("LFM3_MODEL_UNLOADED")
                nativeRequested = false
                _state.value = _state.value.copy(phase = RuntimePhase.INSTALLED, loadedId = null, loadedContext = 0, loadedCpu = null, loadedBackend = null, message = "Model unloaded. Native resources released.")
            } catch (e: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, message = safeError(e, "Unable to unload yet. Stop the response and retry.")) }
        }
    }
    fun checkOpenCl() {
        if (task?.isActive == true || native.loaded || native.generating || downloads.state.value.active) return
        task = scope.launch {
            _state.update { it.copy(phase = RuntimePhase.LOADING, message = "Checking optional OpenCL driver…") }
            try {
                val found = native.openclAvailable()
                _state.update { it.copy(phase = if (it.installed) RuntimePhase.INSTALLED else RuntimePhase.AVAILABLE,
                    message = if (found) "OpenCL driver detected. Model correctness and performance remain experimental." else "No accessible OpenCL driver. CPU remains available.") }
            } catch (_: Exception) { _state.update { it.copy(phase = RuntimePhase.ERROR, message = "OpenCL check failed. Select CPU and load explicitly; nothing was replayed.") } }
        }
    }
    fun remove() {
        if (task?.isActive == true || downloads.state.value.active || native.loaded || native.generating) return
        val id = _state.value.selectedId
        task = scope.launch {
            try {
                installers.getValue(id).remove()
                downloads.forget(id)
                _state.value = _state.value.copy(phase = RuntimePhase.AVAILABLE, installedIds = _state.value.installedIds - id, downloaded = 0, message = "Model files removed.")
            } catch (_: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, message = "Unable to remove model files.") }
        }
    }
    private fun safeError(error: Exception, fallback: String): String = when (error) {
        is IllegalStateException -> error.message?.takeIf { !it.contains('/') && it.length <= 200 } ?: fallback
        else -> fallback
    }
}
