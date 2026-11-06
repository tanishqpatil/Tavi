package app.lfm3.probe

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.history.*
import app.lfm3.probe.inference.RequestPart
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.presentation.ProbeTurn
import app.lfm3.probe.presentation.ReplyStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ViewModelStore
import app.lfm3.probe.presentation.ProbeViewModel
import app.lfm3.probe.inference.DeterministicProbeEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ConversationStorageTest {
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "history-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = root }
    }
    private fun turns(count: Int) = (1..count).flatMap { n -> listOf(ProbeTurn.User(n.toLong(), "question $n", false, false),
        ProbeTurn.Assistant(n.toLong(), "reply $n", ReplyStatus.COMPLETE)) }

    @Test fun viewModelSavesCompletedAndCancelledTurnsAcrossRecreation() = runBlocking {
        val context = context(); val repository = ConversationRepository(context); val media = MediaStore(context)
        val owner = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ProbeViewModel(DeterministicProbeEngine(), media, repository).also { owner.put("first", it) } }
        try {
            withTimeout(10_000) { while (model.state.value.isHistoryBusy) delay(20) }
            withContext(Dispatchers.Main) { model.updateDraft("persistent fixture"); model.submit() }
            withTimeout(10_000) { while (model.state.value.isGenerating || repository.list().isEmpty()) delay(20) }
            val id = model.state.value.conversationId
            withContext(Dispatchers.Main) { model.updateDraft("cancelled fixture"); model.submit() }
            delay(150)
            withContext(Dispatchers.Main) { model.cancel(); model.newChat() }
            withTimeout(10_000) { while (model.state.value.isHistoryBusy) delay(20) }
            assertTrue(model.state.value.turns.isEmpty())
            val stored = repository.load(id)!!
            assertEquals(ReplyStatus.COMPLETE, (stored.turns[1] as ProbeTurn.Assistant).status)
            assertEquals(ReplyStatus.INTERRUPTED, (stored.turns.last() as ProbeTurn.Assistant).status)
            val recreated = withContext(Dispatchers.Main) { ProbeViewModel(DeterministicProbeEngine(), media, ConversationRepository(context)).also { owner.put("second", it) } }
            withTimeout(10_000) { while (recreated.state.value.isHistoryBusy) delay(20) }
            assertEquals(id, recreated.state.value.conversationId)
            assertEquals(stored.turns, recreated.state.value.turns)
            withContext(Dispatchers.Main) { recreated.deleteConversation(id) }
            withTimeout(10_000) { while (recreated.state.value.isHistoryBusy) delay(20) }
            delay(100)
            assertTrue(repository.list().isEmpty())
        } finally { withContext(Dispatchers.Main) { owner.clear() } }
    }

    @Test fun reopenRecoversPartialReplyAndPreservesOlderPages() = runBlocking {
        val context = context(); val store = ConversationRepository(context)
        val chat = SavedConversation(title = "remember me", turns = turns(85) + ProbeTurn.User(86, "unfinished", false, false) + ProbeTurn.Assistant(86, "partial"))
        store.save(chat)
        val reopened = ConversationRepository(context)
        reopened.recoverInterrupted()
        val page = reopened.load(chat.id)!!
        assertEquals(80, page.turns.size)
        assertEquals(ReplyStatus.INTERRUPTED, (page.turns.last() as ProbeTurn.Assistant).status)
        reopened.save(page.copy(draft = "saved draft"))
        assertEquals(86, reopened.count(chat.id))
        assertEquals(172, reopened.load(chat.id, 500)!!.turns.size)
        assertEquals("saved draft", reopened.load(chat.id)!!.draft)
        assertEquals(chat.id, reopened.list("question 1").single().id)
        assertTrue(reopened.list("%_").isEmpty())
        reopened.rename(chat.id, "renamed")
        assertEquals("renamed", reopened.load(chat.id)!!.title)
        reopened.delete(chat.id)
        assertNull(reopened.load(chat.id))
        assertTrue(reopened.references().isEmpty())
    }

    @Test fun archiveRestoresTypedMediaWithoutOverwritingOriginals() = runBlocking {
        val context = context(); val store = ConversationRepository(context); val media = MediaStore(context)
        val photo = media.newFile("jpg").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        val audio = media.newFile("wav").apply { writeBytes(byteArrayOf(5,6,7,8)) }
        val parts = listOf(RequestPart.Image(photo.name), RequestPart.Audio(audio.name, 500))
        val chat = SavedConversation(title = "photo and voice", turns = listOf(ProbeTurn.User(1, "look", true, true, parts), ProbeTurn.Assistant(1, "answer", ReplyStatus.COMPLETE)))
        store.save(chat)
        val archive = ConversationArchive(store, media); val output = ByteArrayOutputStream()
        archive.export(chat.id, output)
        val id = archive.restore(ByteArrayInputStream(output.toByteArray()))
        assertNotEquals(chat.id, id)
        val restored = store.load(id)!!
        val attachments = (restored.turns.first() as ProbeTurn.User).media
        assertEquals(2, attachments.size)
        assertNotEquals(photo.name, ConversationRepository.reference(attachments[0]))
        assertArrayEquals(photo.readBytes(), media.resolve(ConversationRepository.reference(attachments[0])).readBytes())
        assertEquals(500L, (attachments[1] as RequestPart.Audio).durationMs)
        store.delete(chat.id); media.prune(store.references())
        assertFalse(photo.exists()); assertFalse(audio.exists())
        assertTrue(media.resolve(ConversationRepository.reference(attachments[0])).exists())
        // A second store instance must leave durable media intact on process initialization.
        assertTrue(MediaStore(context).resolve(ConversationRepository.reference(attachments[0])).exists())
    }

    @Test fun unsafeAndIncompleteArchivesCannotChangeHistory() = runBlocking {
        val context = context(); val store = ConversationRepository(context); val media = MediaStore(context)
        val original = SavedConversation(title = "keep", turns = turns(1)); store.save(original)
        for (name in listOf("../outside.jpg", "/absolute", "conversation.json")) {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write("invalid".toByteArray()); zip.closeEntry() }
            try { ConversationArchive(store, media).restore(ByteArrayInputStream(out.toByteArray())); fail("Invalid archive accepted") }
            catch (_: Exception) { }
        }
        assertEquals(original.id, store.list().single().id)
        assertTrue(media.directory.listFiles()!!.isEmpty())
    }
}
