package app.lfm3.probe.diagnostics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiagnosticEvent(val time: String, val code: String)

/** Operational log only: callers must never include prompt, image, audio, URI, or path data. */
class AppLog(context: Context) {
    private val file = File(context.filesDir, "diagnostics/app.log")
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries = _entries.asStateFlow()

    fun record(event: String) {
        require(event.matches(Regex("[A-Z0-9_. -]{1,120}"))) { "Diagnostic event contains unsupported data" }
        val line = "${Instant.now()} $event"
        // Diagnostics must never fail a load/download or grow without bound on disk.
        synchronized(fileLock) {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.length() > 128 * 1024) file.writeText("")
                file.appendText("$line\n")
            }
        }
        _entries.value = (_entries.value + line).takeLast(100)
    }
    /** Export only recognized operational codes, even if a legacy/tampered log contains payload text. */
    suspend fun snapshot(): List<DiagnosticEvent> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            if (!file.isFile || file.length() > 129 * 1024) return@synchronized emptyList()
            file.readLines().takeLast(100).mapNotNull { line ->
                if (line.length > 180) return@mapNotNull null
                val parts = line.split(' ', limit = 2)
                if (parts.size != 2 || (parts[1] !in codes && !downloadCode.matches(parts[1]))) return@mapNotNull null
                runCatching { DiagnosticEvent(Instant.parse(parts[0]).toString(), parts[1]) }.getOrNull()
            }
        }
    }
    private companion object {
        val fileLock = Any()
        val codes = setOf("APP_STARTED", "ARTIFACT_SHA256_VERIFIED", "MODEL_PACK_ACTIVATED", "RUNTIME_PROCESS_DISCONNECTED",
            "NATIVE_RUNTIME_INITIALIZED", "LFM3_MODEL_LOADED", "LFM3_MODEL_UNLOADED")
        val downloadCode = Regex("DOWNLOAD_HTTP_[1-5][0-9]{2}_OFFSET_[0-9]{1,16}")
    }
}
