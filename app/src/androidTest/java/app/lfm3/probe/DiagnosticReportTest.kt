package app.lfm3.probe

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.diagnostics.*
import app.lfm3.probe.inference.GenerationLimits
import app.lfm3.probe.models.RuntimeState
import app.lfm3.probe.online.OnlineConfig
import app.lfm3.probe.presentation.GenerationMetrics
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiagnosticReportTest {
    @Test fun reportUsesAnExplicitPayloadFreeSchemaAndMeasuredUnits() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "diagnostic-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try {
            val log = File(root, "diagnostics/app.log").apply { parentFile!!.mkdirs() }
            log.writeText("2026-11-08T00:00:00Z APP_STARTED\n2026-11-08T00:00:01Z PRIVATE_CANARY\n2026-11-08T00:00:02Z https://private.example/key\ninvalid-time APP_STARTED\n2026-11-08T00:00:03Z DOWNLOAD_HTTP_503_OFFSET_123\n")
            val report = DiagnosticReport.collect(context,
                RuntimeState(message = "PRIVATE_CANARY /private/path", selectedId = "PRIVATE_CANARY", loadedId = "PRIVATE_CANARY", nativeInfo = "PRIVATE_CANARY"),
                GenerationLimits(2048, 256), GenerationMetrics(12, 2_000_000_000, 100),
                OnlineConfig(mcpUrl = "https://private.example/key", enabledTools = setOf("PRIVATE_CANARY")))
            assertFalse(report.contains("PRIVATE_CANARY")); assertFalse(report.contains("private.example")); assertFalse(report.contains(root.path))
            val value = JSONObject(report)
            assertEquals(2, value.getJSONArray("events").length())
            assertEquals(2048, value.getJSONObject("generation").getInt("selected_context_tokens"))
            assertEquals(2_000_000_000L, value.getJSONObject("generation").getLong("last_decode_nanos"))
            assertTrue(value.getJSONObject("memory").getLong("system_total_bytes") > 0)
            assertTrue(value.getJSONObject("device").getLong("page_size_bytes") > 0)
            assertEquals("none", value.getJSONObject("runtime").getString("loaded_pack"))
            assertEquals("unavailable", value.getJSONObject("runtime").getString("native_info"))
            assertTrue(report.toByteArray().size <= DiagnosticReport.MAX_BYTES)
            assertTrue(root.walkTopDown().none { it.extension == "json" }) // Collection alone never saves or uploads.
            val exported = File(root, "explicit-export.json").apply { writeText(report) }
            assertEquals(value.toString(), JSONObject(exported.readText()).toString())
        } finally { root.deleteRecursively() }
    }

    @Test fun oversizedLegacyLogIsExcludedWithoutReadingItIntoTheReport() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "diagnostic-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try {
            File(root, "diagnostics/app.log").apply { parentFile!!.mkdirs(); writeText("PRIVATE_CANARY".repeat(12_000)) }
            assertTrue(AppLog(context).snapshot().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
