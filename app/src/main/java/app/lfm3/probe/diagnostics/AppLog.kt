package app.lfm3.probe.diagnostics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

/** Operational log only: callers must never include prompt, image, audio, URI, or path data. */
class AppLog(context: Context) {
    private val file = File(context.filesDir, "diagnostics/app.log")
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries = _entries.asStateFlow()

    fun record(event: String) {
        require(event.matches(Regex("[A-Z0-9_. -]{1,120}"))) { "Diagnostic event contains unsupported data" }
        val line = "${Instant.now()} $event"
        file.parentFile?.mkdirs()
        file.appendText("$line\n")
        _entries.value = (_entries.value + line).takeLast(100)
    }
}
