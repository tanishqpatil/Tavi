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
    val installed: Boolean = false,
    val loaded: Boolean = false,
)

/** Application-owned coordinator survives rotation. Initial downloads intentionally require foreground use. */
class RuntimeManager(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = AppLog(context)
    private val installer = PackInstaller(File(context.noBackupFilesDir, "models"), log::record)
    private val native = LlamaEngine()
    private val preview = DeterministicProbeEngine()
    private val _state = MutableStateFlow(RuntimeState())
    val state = _state.asStateFlow()
    private var task: Job? = null
    val engine = object : InferenceEngine {
        override val descriptor get() = if (native.loaded) native.descriptor else preview.descriptor
        override fun prepare(): Long = if (native.loaded) native.prepare() else 0
        override fun cancel() { if (_state.value.nativeInfo != null) native.cancel() }
        override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) {
            if (native.loaded) native.generate(request, emit) else preview.generate(request, emit)
        }
    }

    init {
        task = scope.launch {
            try {
                val info = NativeLlama.info()
                log.record("NATIVE_RUNTIME_INITIALIZED")
                val valid = installer.verifyInstalled()
                _state.value = RuntimeState(if (valid) RuntimePhase.INSTALLED else RuntimePhase.AVAILABLE, info,
                    if (valid) "Verified text model installed. Ready to load." else "CPU runtime initialized. No model loaded.", installed = valid)
            } catch (_: LinkageError) {
                _state.value = RuntimeState(RuntimePhase.UNAVAILABLE, message = "The ARM64 native runtime is unavailable on this device.")
            } catch (_: Exception) { _state.value = RuntimeState(RuntimePhase.UNAVAILABLE, message = "Native runtime initialization failed.") }
        }
    }

    fun download() {
        if (task?.isActive == true || native.loaded || _state.value.nativeInfo == null || _state.value.installed) return
        task = scope.launch {
            try {
                _state.value = _state.value.copy(phase = RuntimePhase.DOWNLOADING, message = "Downloading and verifying. Keep Tavi open.")
                installer.install { bytes, _ -> _state.value = _state.value.copy(downloaded = bytes) }
                _state.value = _state.value.copy(phase = RuntimePhase.INSTALLED, installed = true, message = "Checksum verified. Ready to load.")
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(phase = RuntimePhase.AVAILABLE, message = "Download paused. Tap Download to resume.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(phase = RuntimePhase.ERROR, message = safeError(e, "Download failed. Check connectivity and storage, then retry."))
            }
        }
    }

    fun pauseDownload() {
        if (_state.value.phase == RuntimePhase.DOWNLOADING) { task?.cancel(); installer.interrupt() }
    }

    fun load() {
        if (task?.isActive == true || !_state.value.installed || native.loaded || native.generating) return
        task = scope.launch {
            try {
                _state.value = _state.value.copy(phase = RuntimePhase.LOADING, message = "Verifying files and allocating model context…")
                check(installer.verifyInstalled()) { "Installed model failed verification. Remove it and download again." }
                native.load(File(installer.installed, TextPack.weights.name).absolutePath)
                log.record("LFM3_TEXT_LOADED")
                _state.value = _state.value.copy(phase = RuntimePhase.LOADED, loaded = true, message = "LFM3 text ready · CPU · 1,024-token context")
            } catch (e: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, loaded = false, message = safeError(e, "Model load failed.")) }
        }
    }

    fun unload() {
        if (task?.isActive == true || native.generating) return
        task = scope.launch {
            native.unload()
            log.record("LFM3_TEXT_UNLOADED")
            _state.value = _state.value.copy(phase = RuntimePhase.INSTALLED, loaded = false, message = "Model unloaded. Native model and context released.")
        }
    }

    fun remove() {
        if (task?.isActive == true || native.loaded || native.generating) return
        task = scope.launch {
            try {
                installer.remove()
                _state.value = _state.value.copy(phase = RuntimePhase.AVAILABLE, installed = false, downloaded = 0, message = "Model files removed.")
            } catch (_: Exception) { _state.value = _state.value.copy(phase = RuntimePhase.ERROR, message = "Unable to remove model files.") }
        }
    }

    private fun safeError(error: Exception, fallback: String): String = when (error) {
        is IllegalStateException -> error.message?.takeIf { !it.contains('/') && it.length <= 200 } ?: fallback
        else -> fallback
    }
}
