package app.lfm3.probe

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.history.*
import app.lfm3.probe.inference.*
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.models.*
import app.lfm3.probe.presentation.*
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimeRecoveryTest {
    @Test fun separateProcessDeathIsReportedAndExplicitReconnectWorks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deaths = CompletableDeferred<Unit>()
        val engine = RemoteLlamaEngine(context) { deaths.complete(Unit) }
        try {
            assertTrue(engine.info().contains("8642541b2"))
            val pid = engine.remotePid
            assertTrue(pid > 0 && pid != Process.myPid())
            Process.killProcess(pid)
            withTimeout(10_000) { deaths.await() }
            assertFalse(engine.loaded)
            assertEquals(0, engine.remotePid)
            assertTrue(engine.info().contains("8642541b2"))
            assertNotEquals(pid, engine.remotePid)
            try { engine.load(ModelCatalog.text, 1024) }
            catch (_: IllegalStateException) { /* A missing pack must fail safely through IPC. */ }
        } finally { engine.unload() }
    }

    /** Explicit local opt-in only; never downloads weights in CI or normal instrumentation runs. */
    @Test fun realNativeDeathPreservesPartialChatAndReloadsWithoutReplay() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runModelTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = PackInstaller(File(context.noBackupFilesDir, "models"))
        if (!installer.verifyInstalled() && args.getString("downloadModelTest") == "true") installer.install { _, _ -> }
        assertTrue("Install the text pack first, or explicitly opt into the test download.", installer.verifyInstalled())
        val engine = RemoteLlamaEngine(context)
        val repository = ConversationRepository(context)
        val owner = ViewModelStore()
        try {
            engine.info(); engine.load(ModelCatalog.text, 2048)
            try { PackLease.acquire(File(context.noBackupFilesDir, "models"), ModelCatalog.text, shared = false).use { }; fail("Loaded pack was not leased") }
            catch (_: IllegalStateException) { /* OS lease must protect mmap-backed files in the other process. */ }
            val model = withContext(Dispatchers.Main) { ProbeViewModel(engine, MediaStore(context), repository).also { owner.put("recovery", it) } }
            withTimeout(10_000) { while (model.state.value.isHistoryBusy) delay(20) }
            withContext(Dispatchers.Main) { model.newChat() }
            withTimeout(10_000) { while (model.state.value.isHistoryBusy) delay(20) }
            withContext(Dispatchers.Main) { model.outputLimit = 512; model.updateDraft("Count from one to one hundred, with an explanation for each number."); model.submit() }
            withTimeout(180_000) { while (model.state.value.turns.filterIsInstance<ProbeTurn.Assistant>().lastOrNull()?.text.isNullOrBlank()) delay(5) }
            val id = model.state.value.conversationId
            Process.killProcess(engine.remotePid)
            withTimeout(15_000) { while (model.state.value.isGenerating) delay(20) }
            assertFalse(engine.loaded)
            assertEquals(ReplyStatus.INTERRUPTED, (model.state.value.turns.last() as ProbeTurn.Assistant).status)
            assertTrue((model.state.value.turns.last() as ProbeTurn.Assistant).text.isNotBlank())
            withTimeout(10_000) {
                while ((repository.load(id)?.turns?.lastOrNull() as? ProbeTurn.Assistant)?.status != ReplyStatus.INTERRUPTED) delay(20)
            }
            val previousCount = model.state.value.turns.size
            engine.info(); engine.load(ModelCatalog.text, 2048)
            assertEquals(previousCount, model.state.value.turns.size) // Reload never resubmits.
            withContext(Dispatchers.Main) { model.outputLimit = 64; model.updateDraft("What is 2 plus 2? Reply briefly."); model.submit() }
            withTimeout(180_000) { while (model.state.value.isGenerating) delay(20) }
            val reply = model.state.value.turns.last() as ProbeTurn.Assistant
            assertEquals(ReplyStatus.COMPLETE, reply.status)
            assertTrue(reply.text.contains("4"))
            // Cancellation must acknowledge remote shutdown before the session is reused.
            withContext(Dispatchers.Main) { model.updateDraft("Count from one to one hundred."); model.submit() }
            delay(100)
            withContext(Dispatchers.Main) { model.cancel(); model.newChat() }
            withTimeout(10_000) { while (model.state.value.isHistoryBusy) delay(20) }
            assertTrue(model.state.value.turns.isEmpty())
        } finally { withContext(Dispatchers.Main) { owner.clear() }; engine.unload() }
    }
}
