package app.lfm3.probe

import android.content.ClipData
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.history.*
import app.lfm3.probe.inference.*
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.presentation.*
import app.lfm3.probe.sharing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShareInputTest {
    @Test fun parserRejectsHostileInputAndNeverResolvesRemoteImages() {
        fun text(value: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        assertEquals("https://example.test/", ShareInput.parse(text("https://example.test/"))!!.text)
        assertNotNull(ShareInput.parse(text("x".repeat(8001)))!!.error)
        assertNotNull(ShareInput.parse(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png"))!!.error)
        assertNotNull(ShareInput.parse(text("hello").setType("text/html"))!!.error)
        for (path in listOf("file:///private/photo.jpg", "https://example.test/photo.jpg", "content:///photo")) {
            assertNotNull(ShareInput.parse(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, Uri.parse(path)))!!.error)
        }
        val photo = Uri.parse("content://test.example/photo")
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo)
        assertEquals(photo, ShareInput.parse(intent)!!.image)
        intent.clipData = ClipData.newRawUri("mismatch", Uri.parse("content://other.example/photo"))
        assertNotNull(ShareInput.parse(intent)!!.error)
        val inbox = ShareInbox(); inbox.receive(text("first")); val first = inbox.pending.value!!.id
        inbox.receive(text("second")); inbox.dismiss(first)
        assertEquals("second", inbox.pending.value!!.text)
        inbox.dismiss(inbox.pending.value!!.id); assertNull(inbox.pending.value)
    }

    private class CountingEngine : InferenceEngine {
        var generations = 0
        override val descriptor = EngineDescriptor("share-fixture", Capability.entries.toSet(), EngineKind.DETERMINISTIC_PROBE)
        override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) { generations++; error("Import must not execute inference") }
    }
    private suspend fun settled(model: ProbeViewModel) { withTimeout(5000) { while (model.state.value.isHistoryBusy) delay(20) } }

    @Test fun importedDraftSurvivesRecreationAndKeepsThePreviousChatAndAttachments() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "share-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        val source = File(base.cacheDir, "captures/${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        val uri = FileProvider.getUriForFile(base, "${base.packageName}.files", source)
        val media = MediaStore(context); val repository = ConversationRepository(context); val engine = CountingEngine()
        val originalPhoto = media.importPhoto(uri)
        // Camera-capture URIs are one-shot: import deliberately removes our temporary source.
        media.resolve(originalPhoto).copyTo(source, overwrite = true)
        val originalAudio = media.newFile("wav").apply { writeBytes(byteArrayOf(0)) }.name
        val original = SavedConversation(title = "Original", draft = "previous draft",
            draftMedia = listOf(RequestPart.Image(originalPhoto), RequestPart.Audio(originalAudio, 600)))
        repository.save(original)
        val firstOwner = ViewModelStore(); val secondOwner = ViewModelStore()
        try {
            val first = withContext(Dispatchers.Main) { ProbeViewModel(engine, media, repository).also { firstOwner.put("vm", it) } }
            settled(first); assertEquals(original.id, first.state.value.conversationId)
            withContext(Dispatchers.Main) { first.acceptSharedInput("fixture-share", "https://example.test/", uri) }
            settled(first)
            assertEquals("fixture-share", first.state.value.acceptedShareId)
            assertEquals(0, engine.generations); assertTrue(first.state.value.turns.isEmpty())
            val sharedId = first.state.value.conversationId
            val sharedPhoto = checkNotNull(first.state.value.photoReference)
            assertNotEquals(originalPhoto, sharedPhoto)
            assertEquals(original, repository.load(original.id))
            assertEquals(listOf(RequestPart.Image(sharedPhoto)), repository.load(sharedId)!!.draftMedia)
            withContext(Dispatchers.Main) { firstOwner.clear() }
            val second = withContext(Dispatchers.Main) { ProbeViewModel(engine, media, ConversationRepository(context)).also { secondOwner.put("vm", it) } }
            settled(second)
            assertEquals(sharedId, second.state.value.conversationId); assertEquals(sharedPhoto, second.state.value.photoReference)
            assertTrue(media.resolve(sharedPhoto).isFile)
            withContext(Dispatchers.Main) { second.clearPhoto() }
            withTimeout(3000) { while (repository.load(sharedId)!!.draftMedia.isNotEmpty()) delay(30) }
            withContext(Dispatchers.Main) { second.openConversation(original.id) }; settled(second)
            assertEquals(originalPhoto, second.state.value.photoReference)
            assertEquals(originalAudio, second.state.value.audioReference)
            assertFalse(second.state.value.isRecording)
            withContext(Dispatchers.Main) { second.openConversation(sharedId) }; settled(second)
            assertNull(second.state.value.photoReference) // Attachments cannot bleed across chats.
            assertNull(second.state.value.audioReference)
            assertEquals(0, engine.generations)
        } finally { withContext(Dispatchers.Main) { firstOwner.clear(); secondOwner.clear() }; source.delete(); root.deleteRecursively() }
    }

    @Test fun failedPhotoImportLeavesTheOriginalDraftUntouched() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "share-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        val repository = ConversationRepository(context); val original = SavedConversation(draft = "retained draft")
        repository.save(original)
        val owner = ViewModelStore(); val engine = CountingEngine()
        try {
            val model = withContext(Dispatchers.Main) { ProbeViewModel(engine, MediaStore(context), repository).also { owner.put("vm", it) } }
            settled(model)
            withContext(Dispatchers.Main) { model.acceptSharedInput("bad-photo", "incoming", Uri.parse("content://missing.tavi.fixture/photo")) }
            settled(model)
            assertEquals(original.id, model.state.value.conversationId); assertEquals("retained draft", model.state.value.draft)
            assertNotNull(model.state.value.shareImportError); assertNull(model.state.value.acceptedShareId)
            assertEquals(1, repository.list().size); assertEquals(0, engine.generations)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; root.deleteRecursively() }
    }

    @Test fun versionTwoMigrationRetainsOldMessagesAndTextDraft() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "share-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        val file = File(root, "history/conversations.db").apply { parentFile!!.mkdirs() }
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL,real INTEGER NOT NULL,engine TEXT NOT NULL,draft TEXT NOT NULL,speak INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE messages(chat TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,turn INTEGER NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,status TEXT NOT NULL,media TEXT NOT NULL,receipt TEXT NOT NULL DEFAULT '',PRIMARY KEY(chat,turn,role))")
                db.execSQL("INSERT INTO conversations VALUES('old','Retained',1,0,'preview','retained text draft',0)")
                db.execSQL("INSERT INTO messages VALUES('old',1,'user','question','COMPLETE','[]','')")
                db.execSQL("INSERT INTO messages VALUES('old',1,'assistant','reply','COMPLETE','[]','')")
                db.version = 2
            }
            val repository = ConversationRepository(context); val chat = checkNotNull(repository.load("old"))
            assertEquals("retained text draft", chat.draft); assertEquals(2, chat.turns.size); assertTrue(chat.draftMedia.isEmpty())
            repository.save(chat); assertEquals(chat, repository.load("old"))
        } finally { root.deleteRecursively() }
    }
}
