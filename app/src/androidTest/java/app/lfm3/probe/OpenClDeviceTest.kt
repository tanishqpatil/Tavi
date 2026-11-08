package app.lfm3.probe

import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.*
import app.lfm3.probe.models.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OpenClDeviceTest {
    @Test fun optionalDriverFailureLeavesCpuAndPackLeaseUsable() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runModelTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "models")
        assumeTrue(PackInstaller(root, pack = ModelCatalog.text).verifyInstalled())
        val engine = RemoteLlamaEngine(context)
        try {
            engine.info()
            val available = engine.openclAvailable()
            if (args.getString("expectOpenClUnavailable") == "true") assertFalse(available)
            if (!available) {
                try { engine.load(ModelCatalog.text, 1024, execution = RuntimeBackend.OPENCL); fail("Unavailable driver loaded") }
                catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("OpenCL")) }
                assertFalse(engine.loaded)
                PackLease.acquire(root, ModelCatalog.text, shared = false).use { }
            }
            engine.load(ModelCatalog.text, 1024, execution = RuntimeBackend.CPU)
            val output = StringBuilder()
            withTimeout(120_000) { engine.generate(InferenceRequest(1, listOf(RequestPart.Text("What is two plus two? Answer briefly.")),
                generationTicket = engine.prepare(), outputLimit = 128)) { if (it is InferenceEvent.TextDelta) output.append(it.value) } }
            assertTrue(Regex("(?i)\\b(4|four)\\b").containsMatchIn(output))
        } finally { withContext(NonCancellable) { engine.cancel(); engine.unload() } }
    }
}
