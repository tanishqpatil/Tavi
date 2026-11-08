package app.lfm3.probe

import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.*
import app.lfm3.probe.models.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** An unavailable CPU profile must fail before SDK execution, release its lease and preserve GGUF recovery. */
class LiteRtCompatibilityDeviceTest {
    @Test fun unsupportedCpuAndProfilesLeavePortableInferenceUsable() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.LITERT_EXPERIMENTAL && args.getString("expectLiteRtCpuUnavailable") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pack = ModelCatalog.get("lfm3-omni-litert")
        val control = ModelCatalog.get("lfm3-vl")
        val root = File(context.noBackupFilesDir, "models")
        assumeTrue(PackInstaller(root, pack = pack).verifyInstalled())
        assumeTrue(PackInstaller(root, pack = control).verifyInstalled())
        val engine = RemoteLlamaEngine(context)
        try {
            engine.info()
            for ((tokens, backend, reason) in listOf(
                Triple(8192, RuntimeBackend.CPU, "4096"),
                Triple(1024, RuntimeBackend.OPENCL, "CPU only"),
                Triple(1024, RuntimeBackend.CPU, "SME2"),
            )) {
                try { engine.load(pack, tokens, execution = backend); fail("Unsupported profile loaded") }
                catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(reason)) }
                assertFalse(engine.loaded)
                PackLease.acquire(root, pack, shared = false).use { }
            }
            engine.load(control, 1024)
            val body = StringBuilder()
            withTimeout(120_000) { engine.generate(InferenceRequest(1, listOf(RequestPart.Text("What is two plus two? Answer briefly.")),
                generationTicket = engine.prepare(), outputLimit = 128)) { if (it is InferenceEvent.TextDelta) body.append(it.value) } }
            assertTrue(Regex("(?i)\\b(4|four)\\b").containsMatchIn(body))
        } finally { withContext(NonCancellable) { engine.cancel(); engine.unload() } }
    }
}
