package app.lfm3.probe.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lfm3.probe.BuildConfig
import app.lfm3.probe.inference.CpuOptions
import app.lfm3.probe.inference.RuntimeBackend
import app.lfm3.probe.models.RuntimeState
import app.lfm3.probe.models.ModelRuntime
import app.lfm3.probe.TaviApplication

@Composable
internal fun CpuSettings(runtime: RuntimeState) {
    val context = LocalContext.current
    val settings = remember(context) { TaviSettings(context) }
    var cpu by remember { mutableStateOf(settings.cpu) }
    val liteRt = runtime.selected.runtime == ModelRuntime.LITERT
    var backend by remember(liteRt) { mutableStateOf(if (liteRt) RuntimeBackend.CPU else settings.backend) }
    val enabled = !runtime.loaded && !runtime.busy
    fun update(value: CpuOptions) { settings.cpu = value; cpu = value }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Execution backend", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeBackend.entries.filter { !liteRt || it == RuntimeBackend.CPU }.forEach { choice ->
                FilterChip(backend == choice, { settings.backend = choice; backend = choice },
                    enabled = enabled && (choice == RuntimeBackend.CPU || BuildConfig.OPENCL_COMPILED),
                    label = { Text(if (choice == RuntimeBackend.CPU) "CPU" else "OpenCL · Experimental") })
            }
        }
        Text(if (liteRt) "Experimental LiteRT CPU graphs. Context allocation stays 4096 even with a smaller conversation limit. Prefill uses the largest available signature within your batch setting (128 uses 64)." else "OpenCL offloads the language model; media stays on CPU. It requires an accessible phone driver. Failed loads do not switch engines or resend messages.", style = MaterialTheme.typography.bodySmall)
        if (BuildConfig.OPENCL_COMPILED && !liteRt) TextButton(onClick = { (context.applicationContext as TaviApplication).runtime.checkOpenCl() }, enabled = enabled) { Text("Check OpenCL driver") }
        Text(runtime.message, style = MaterialTheme.typography.bodySmall)
        Text("CPU execution", style = MaterialTheme.typography.titleMedium)
        Text("Unload the model before changing these settings. More threads or a larger batch may increase heat or memory without improving speed.", style = MaterialTheme.typography.bodySmall)
        CpuChoice("Response threads", cpu.threads, CpuOptions.threadChoices, enabled) { update(cpu.copy(threads = it)) }
        CpuChoice(if (liteRt) "Media graph threads" else "Prefill / media threads", cpu.prefillThreads, CpuOptions.threadChoices, enabled) { update(cpu.copy(prefillThreads = it)) }
        if (liteRt) Text("Response threads also apply to language-model prefill in this prototype.", style = MaterialTheme.typography.bodySmall)
        CpuChoice("Prefill batch", cpu.batch, CpuOptions.batchChoices, enabled) { update(cpu.copy(batch = it)) }
        if (!liteRt) Text(if (BuildConfig.CPU_KLEIDIAI_COMPILED) "Optimized CPU kernels included; the runtime selects compatible operations." else "Portable CPU comparison build; KleidiAI disabled.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CpuChoice(title: String, selected: Int, values: List<Int>, enabled: Boolean, change: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { n -> FilterChip(selected == n, { change(n) }, enabled = enabled, label = { Text("$n") }) }
    }
}
