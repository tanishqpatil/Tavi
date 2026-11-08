package app.lfm3.probe

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.*
import app.lfm3.probe.models.*
import app.lfm3.probe.presentation.parseThinking
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Same synthetic fixture across builds. No weights/network/history changes or automatic calibration. */
@RunWith(AndroidJUnit4::class)
class CpuBenchmarkTest {
    @Test fun installedTextCpuComparison() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runCpuBenchmark") == "true")
        val label = args.getString("benchmarkLabel") ?: "local"
        require(label.matches(Regex("[a-zA-Z0-9_-]{1,40}")))
        val cpu = CpuOptions(args.getString("cpuThreads")?.toInt() ?: 4,
            args.getString("prefillThreads")?.toInt() ?: 4, args.getString("prefillBatch")?.toInt() ?: 128)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as TaviApplication
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(30_000) { while (app.runtime.state.value.nativeInfo == null || app.runtime.state.value.busy) delay(100) }
        check(!app.runtime.state.value.loaded && !app.downloads.state.value.active)
        assertTrue(PackInstaller(File(context.noBackupFilesDir, "models"), pack = ModelCatalog.text).verifyInstalled())
        val engine = RemoteLlamaEngine(context)
        val samples = JSONArray()
        val report = JSONObject().put("version", BuildConfig.VERSION_NAME).put("kleidiai_compiled", BuildConfig.CPU_KLEIDIAI_COMPILED)
            .put("threads", cpu.threads).put("prefill_threads", cpu.prefillThreads).put("batch", cpu.batch)
            .put("context", 2048).put("samples", samples).put("status", "RUNNING")
            .put("note", "Synthetic emulator comparison, not phone performance; one warmup excluded.")
        val reportFile = File(context.noBackupFilesDir, "model-qa").apply { mkdirs() }.resolve("cpu-$label.json")
        fun checkpoint() { reportFile.writeText(report.toString(2)) }
        checkpoint()
        try {
            engine.load(ModelCatalog.text, 2048, cpu)
            for (trial in 0..5) {
                val text = StringBuilder(); var usage: InferenceEvent.Usage? = null; var done = false; var first: Long? = null
                val start = System.nanoTime()
                val prompt = "The reference color is violet. ".repeat(32) + "What is the reference color? Answer with only the color."
                withTimeout(120_000) { engine.generate(InferenceRequest(trial.toLong(), listOf(RequestPart.Text(prompt)),
                    generationTicket = engine.prepare(), outputLimit = 128)) { event -> when (event) {
                    is InferenceEvent.TextDelta -> { if (first == null) first = System.nanoTime() - start; text.append(event.value) }
                    is InferenceEvent.Usage -> usage = event
                    is InferenceEvent.Completed -> done = true
                    is InferenceEvent.Failed -> error(event.reason)
                } } }
                val body = parseThinking(text.toString(), false).filterNot { it.thinking }.joinToString("") { it.text }
                assertTrue(done && body.contains("violet", true))
                val stats = checkNotNull(usage)
                assertTrue(stats.prefillNanos > 0 && stats.outputTokens > 0 && stats.decodeNanos > 0)
                if (trial > 0) samples.put(JSONObject().put("trial", trial).put("wall_nanos", System.nanoTime() - start)
                    .put("first_text_nanos", first).put("prefill_nanos", stats.prefillNanos).put("tokens", stats.outputTokens)
                    .put("decode_nanos", stats.decodeNanos).put("optimized_cpu_buffer", stats.optimizedCpuBuffer))
                checkpoint()
            }
            report.put("status", "PASS"); checkpoint()
        } finally { withContext(NonCancellable) { engine.cancel(); engine.unload() } }
    }
}
