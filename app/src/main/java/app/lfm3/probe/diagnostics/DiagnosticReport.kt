package app.lfm3.probe.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import app.lfm3.probe.BuildConfig
import app.lfm3.probe.inference.GenerationLimits
import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.models.RuntimeState
import app.lfm3.probe.online.OnlineConfig
import app.lfm3.probe.presentation.GenerationMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Explicit schema, not a dump of application state. It cannot receive conversations, URIs or credentials. */
object DiagnosticReport {
    const val MAX_BYTES = 32 * 1024
    suspend fun collect(context: Context, runtime: RuntimeState, limits: GenerationLimits,
                        metrics: GenerationMetrics, online: OnlineConfig): String = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val processes = manager.runningAppProcesses.orEmpty().filter {
            it.uid == Process.myUid() && it.processName in setOf(context.packageName, context.packageName + ":inference")
        }.take(2)
        val pss = runCatching { manager.getProcessMemoryInfo(processes.map { it.pid }.toIntArray()).toList() }.getOrDefault(emptyList())
        fun pack(id: String?) = id?.takeIf { value -> ModelCatalog.packs.any { it.id == value } } ?: "none"
        fun label(value: String) = value.filter { it.isLetterOrDigit() || it in " ._()+-" }.take(120)
        val events = AppLog(context).snapshot()
        val report = JSONObject().apply {
            put("format_version", 1); put("created_utc", Instant.now().toString())
            put("app", JSONObject().put("version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
                .put("build_type", BuildConfig.BUILD_TYPE).put("application_id", BuildConfig.APPLICATION_ID))
            put("device", JSONObject().put("manufacturer", label(Build.MANUFACTURER)).put("model", label(Build.MODEL))
                .put("android_release", label(Build.VERSION.RELEASE)).put("sdk", Build.VERSION.SDK_INT)
                .put("security_patch", label(Build.VERSION.SECURITY_PATCH)).put("abis", JSONArray(Build.SUPPORTED_ABIS.take(4)))
                .put("page_size_bytes", Os.sysconf(OsConstants._SC_PAGESIZE)))
            put("memory", JSONObject().put("system_total_bytes", memory.totalMem).put("system_available_bytes", memory.availMem)
                .put("system_low_memory", memory.lowMemory).put("process_pss_kib", JSONArray().apply {
                    processes.forEachIndexed { index, process -> pss.getOrNull(index)?.takeIf { it.totalPss > 0 }?.let {
                        put(JSONObject().put("role", if (process.processName.endsWith(":inference")) "inference" else "ui").put("pss_kib", it.totalPss))
                    } }
                }).put("measurement_note", "Snapshot only; Android may rate-limit PSS samples. Not a device compatibility or peak-memory result."))
            put("runtime", JSONObject().put("phase", runtime.phase.name).put("selected_pack", pack(runtime.selectedId))
                .put("loaded_pack", pack(runtime.loadedId)).put("loaded_context_tokens", runtime.loadedContext)
                .put("cpu_kernels_compiled", BuildConfig.CPU_KLEIDIAI_COMPILED)
                .put("opencl_compiled", BuildConfig.OPENCL_COMPILED).put("loaded_backend", runtime.loadedBackend?.name ?: "none")
                .put("litert_lab_compiled", BuildConfig.LITERT_EXPERIMENTAL)
                .put("loaded_cpu_threads", runtime.loadedCpu?.threads ?: JSONObject.NULL)
                .put("loaded_prefill_threads", runtime.loadedCpu?.prefillThreads ?: JSONObject.NULL)
                .put("loaded_prefill_batch", runtime.loadedCpu?.batch ?: JSONObject.NULL)
                .put("installed_packs", JSONArray(runtime.installedIds.filter { pack(it) == it }.sorted()))
                .put("native_info", runtime.nativeInfo?.takeIf { it.matches(Regex("llama\\.cpp v[0-9.]+ / [a-f0-9]{7,40} / ARM64 CPU")) } ?: "unavailable"))
            put("generation", JSONObject().put("selected_context_tokens", limits.context).put("output_limit_tokens", limits.output)
                .put("last_output_tokens", metrics.outputTokens ?: JSONObject.NULL).put("last_decode_nanos", metrics.decodeNanos ?: JSONObject.NULL)
                .put("last_first_text_ms", metrics.firstTextMs ?: JSONObject.NULL)
                .put("last_prefill_nanos", metrics.prefillNanos ?: JSONObject.NULL).put("optimized_cpu_buffer_observed", metrics.optimizedCpuBuffer)
                .put("last_audio_samples", metrics.audioSamples ?: JSONObject.NULL).put("last_audio_rate", metrics.audioRate ?: JSONObject.NULL)
                .put("last_first_audio_ms", metrics.firstAudioMs ?: JSONObject.NULL))
            put("online", JSONObject().put("mode", online.mode.name).put("provider", online.provider.name).put("enabled_custom_tool_count", online.enabledTools.size))
            put("events", JSONArray().apply { events.forEach { put(JSONObject().put("time", it.time).put("code", it.code)) } })
            put("privacy", "No prompts, replies, media, source links, server URLs, tool arguments, keys, account identifiers, serials or private paths. Save/share only after reviewing this snapshot.")
        }.toString(2)
        check(report.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Diagnostic report exceeded its size limit." }
        report
    }
}
