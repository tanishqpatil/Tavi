package app.lfm3.probe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.*
import app.lfm3.probe.models.PackInstaller
import app.lfm3.probe.models.TextPack
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in integration test; requires the verified pack installed by Tavi first. */
@RunWith(AndroidJUnit4::class)
class Lfm3DeviceTest {
    @Test fun installedModelStreamsRemembersCancelsAndReloads() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Real-model test requires explicit opt-in.", args.getString("runModelTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pack = PackInstaller(File(context.noBackupFilesDir, "models"))
        assertTrue("Download the verified text pack in Tavi before this test.", pack.verifyInstalled())
        NativeLlama.info()
        val engine = LlamaEngine()
        try {
            engine.load(File(pack.installed, TextPack.weights.name).absolutePath)
            val output = StringBuilder()
            var stats: InferenceEvent.Usage? = null
            val ticket1 = engine.prepare()
            withTimeout(180_000) {
                engine.generate(InferenceRequest(1, listOf(RequestPart.Text("Reply with only the word hello.")), generationTicket = ticket1)) {
                    if (it is InferenceEvent.TextDelta) output.append(it.value)
                    if (it is InferenceEvent.Usage) stats = it
                }
            }
            assertTrue("Expected a grounded short response.", output.toString().lowercase().contains("hello"))
            val measured = requireNotNull(stats)
            assertTrue(measured.outputTokens > 0 && measured.decodeNanos > 0)
            Log.i("TaviDeviceTest", "TEXT_OK tokens=${measured.outputTokens} decodeNanos=${measured.decodeNanos}")
            output.clear()
            val ticket2 = engine.prepare()
            withTimeout(180_000) {
                engine.generate(InferenceRequest(2, listOf(RequestPart.Text("What word did I ask you to remember? Reply with only that word.")),
                    listOf(ChatMessage("user", "Remember the word ORBIT."), ChatMessage("assistant", "I will remember ORBIT.")), ticket2)) {
                    if (it is InferenceEvent.TextDelta) output.append(it.value)
                }
            }
            assertTrue(output.toString().contains("ORBIT", ignoreCase = true))
            Log.i("TaviDeviceTest", "HISTORY_OK")
            val firstToken = CompletableDeferred<Unit>()
            val ticket3 = engine.prepare()
            val job = launch(Dispatchers.IO) {
                engine.generate(InferenceRequest(3, listOf(RequestPart.Text("Count from 1 to 100, one number per line.")), generationTicket = ticket3)) {
                    if (it is InferenceEvent.TextDelta) firstToken.complete(Unit)
                }
            }
            withTimeout(180_000) { firstToken.await() }
            engine.cancel(); job.cancelAndJoin()
            // Reconstruct a turn in the same hybrid context after cancellation.
            output.clear()
            val recoveredTicket = engine.prepare()
            withTimeout(180_000) {
                engine.generate(InferenceRequest(4, listOf(RequestPart.Text("Reply with only the word ready.")), generationTicket = recoveredTicket)) {
                    if (it is InferenceEvent.TextDelta) output.append(it.value)
                }
            }
            assertTrue(output.toString().contains("ready", ignoreCase = true))
            Log.i("TaviDeviceTest", "CANCEL_RECOVERY_OK")
            engine.unload()
            assertFalse(engine.loaded)
            engine.load(File(pack.installed, TextPack.weights.name).absolutePath)
            val ticket4 = engine.prepare()
            output.clear()
            withTimeout(180_000) {
                engine.generate(InferenceRequest(4, listOf(RequestPart.Text("Reply with only the word ready.")), generationTicket = ticket4)) {
                    if (it is InferenceEvent.TextDelta) output.append(it.value)
                }
            }
            assertTrue(output.toString().contains("ready", ignoreCase = true))
            Log.i("TaviDeviceTest", "CANCEL_UNLOAD_RELOAD_OK")
        } finally { engine.cancel(); engine.unload() }
    }
}
