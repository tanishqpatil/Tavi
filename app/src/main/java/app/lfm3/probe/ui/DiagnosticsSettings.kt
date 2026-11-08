package app.lfm3.probe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lfm3.probe.TaviApplication
import app.lfm3.probe.diagnostics.DiagnosticReport
import app.lfm3.probe.inference.GenerationLimits
import app.lfm3.probe.models.RuntimeState
import app.lfm3.probe.presentation.GenerationMetrics
import kotlinx.coroutines.*

@Composable
internal fun DiagnosticsSettings(runtime: RuntimeState, limits: GenerationLimits, metrics: GenerationMetrics) {
    val context = LocalContext.current
    val application = context.applicationContext as TaviApplication
    val online by application.online.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exporting; exporting = null
        if (uri != null && snapshot != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    check(snapshot.toByteArray().size <= DiagnosticReport.MAX_BYTES)
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(snapshot.toByteArray(Charsets.UTF_8)) }
                }
                notice = "Diagnostics saved. Review the file before sharing it."; report = null
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { notice = "Could not save diagnostics. Check the selected destination and try again." }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Phone test diagnostics", style = MaterialTheme.typography.titleSmall)
        Text("Preview a small report of device/build, model/settings, memory snapshot and operational events. Conversations, photos, recordings, private endpoints and credentials are excluded. Nothing is uploaded automatically.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy && exporting == null, onClick = {
            scope.launch {
                busy = true; notice = null
                try { report = DiagnosticReport.collect(context, runtime, limits, metrics, online) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { notice = "Could not collect diagnostics. Try again when the app is idle." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Preparing report…" else "Preview diagnostics") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    report?.let { snapshot -> AlertDialog(
        onDismissRequest = { if (exporting == null) report = null }, title = { Text("Review diagnostics") },
        text = { SelectionContainer { Text(snapshot, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } },
        confirmButton = { TextButton(enabled = !busy && exporting == null, onClick = {
            exporting = snapshot; save.launch("Tavi-diagnostics-${java.time.LocalDate.now()}.json")
        }) { Text("Save report…") } },
        dismissButton = { TextButton(enabled = exporting == null, onClick = { report = null }) { Text("Close") } },
    ) }
}
