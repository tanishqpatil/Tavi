package app.lfm3.probe

import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.*
import app.lfm3.probe.models.PackInstaller
import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.online.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in: installed model, real grammar and optional live provider. No weights or network in CI. */
@RunWith(AndroidJUnit4::class)
class OnlineRoutingDeviceTest {
    @Test fun installedModelRoutesThenAnswersWithoutLeakingTheGrammar() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runModelTest") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val pack = PackInstaller(File(base.noBackupFilesDir, "models"))
        assertTrue("Install the verified text pack first.", pack.verifyInstalled())
        val root = File(base.cacheDir, "router-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        val online = OnlineTools(context)
        val engine = RemoteLlamaEngine(base)
        try {
            engine.load(ModelCatalog.text, 2048)
            val cases = listOf(
                "Hello!" to false,
                "What is 7 plus 5?" to false,
                "Translate good morning into French." to false,
                "Search the web for the official Android developer documentation." to true,
                "What are today's latest technology news headlines?" to true,
            )
            for ((index, case) in cases.withIndex()) {
                val request = InferenceRequest(index.toLong() + 1, listOf(RequestPart.Text(case.first)), generationTicket = engine.prepare())
                val decision = online.decide(engine, request, emptyList())
                Log.i("TaviOnlineTest", "ROUTE_CASE_$index=${decision.javaClass.simpleName}")
                assertEquals("Routing case $index failed", case.second, decision is ToolDecision.Web)
            }
            // A normal answer after routing must not be constrained to JSON.
            val output = StringBuilder()
            engine.generate(InferenceRequest(10, listOf(RequestPart.Text("Reply with only the word ready.")), generationTicket = engine.prepare(), outputLimit = 64)) {
                if (it is InferenceEvent.TextDelta) output.append(it.value)
            }
            assertTrue(output.toString().contains("ready", true)); assertFalse(output.toString().trim().startsWith('{'))
            if (args.getString("runOnlineTest") == "true") {
                online.configure(OnlineConfig(mode = OnlineMode.AUTO))
                var receipt: ToolReceipt? = null
                output.clear()
                withTimeout(180_000) {
                    online.generate(engine, InferenceRequest(11,
                        listOf(RequestPart.Text("Search the web for the official Android Developers WorkManager documentation and tell me its purpose.")),
                        generationTicket = engine.prepare(), outputLimit = 128), 2048,
                        { if (it.receipt != null) receipt = it.receipt },
                        { if (it is InferenceEvent.TextDelta) output.append(it.value) })
                }
                assertNotNull(receipt); assertTrue(receipt!!.sources.isNotEmpty()); assertTrue(output.isNotBlank())
                Log.i("TaviOnlineTest", "MODEL_SEARCH_ANSWER_OK sources=${receipt.sources.size}")
            }
        } finally { engine.unload(); root.deleteRecursively() }
    }
}
