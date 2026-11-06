package app.lfm3.probe.history

import app.lfm3.probe.inference.RequestPart
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.presentation.ProbeTurn
import app.lfm3.probe.presentation.ReplyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Versioned, bounded archive. Imports stage and validate every byte before touching history. */
class ConversationArchive(private val repository: ConversationRepository, private val media: MediaStore) {
    suspend fun export(id: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val count = repository.count(id)
        require(count <= 500) { "Export supports up to 500 turns per conversation." }
        val chat = checkNotNull(repository.load(id, 500)) { "Conversation no longer exists." }
        val attachments = chat.turns.filterIsInstance<ProbeTurn.User>().flatMap { it.media }
            .map { ConversationRepository.reference(it) }.distinct().map(media::resolve)
        require(attachments.size <= 64 && attachments.sumOf { it.length() } <= MAX_BYTES) { "Archive exceeds the attachment budget." }
        val manifest = JSONObject().apply {
            put("version", 1); put("title", chat.title); put("real", chat.real); put("engine", chat.engine)
            put("messages", JSONArray().apply { chat.turns.forEach { turn -> put(JSONObject().apply {
                put("turn", turn.id); put("role", if (turn is ProbeTurn.User) "user" else "assistant")
                put("text", when (turn) { is ProbeTurn.User -> turn.text; is ProbeTurn.Assistant -> turn.text })
                put("status", if (turn is ProbeTurn.Assistant) turn.status.name else "COMPLETE")
                put("media", if (turn is ProbeTurn.User) ConversationRepository.encodeMedia(turn.media) else JSONArray())
                if (turn is ProbeTurn.Assistant && turn.receipt != null) put("receipt", turn.receipt.json())
            }) } })
            put("attachments", JSONArray().apply { attachments.forEach { file -> put(JSONObject().apply {
                put("name", file.name); put("bytes", file.length()); put("sha256", digest(file))
            }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST) { "Conversation exceeds the export text budget." }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("conversation.json")); zip.write(manifest); zip.closeEntry()
            attachments.forEach { file ->
                zip.putNextEntry(ZipEntry("media/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    suspend fun restore(input: InputStream): String = withContext(Dispatchers.IO) {
        val staging = File(media.directory.parentFile, "restore-${UUID.randomUUID()}").apply { check(mkdir()) }
        val published = mutableListOf<File>()
        try {
            var total = 0L
            val entries = mutableSetOf<String>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entries.add(entry.name) && entries.size <= 65) { "Duplicate or unsupported archive entry." }
                    val name = when {
                        entry.name == "conversation.json" -> entry.name
                        entry.name.matches(Regex("media/[a-f0-9-]{36}\\.(jpg|wav)")) -> entry.name.substringAfter('/')
                        else -> error("Unsupported or unsafe archive path.")
                    }
                    var bytes = 0L
                    val limit = if (name == "conversation.json") MAX_MANIFEST.toLong() else MAX_FILE
                    File(staging, name).outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            total += n; bytes += n
                            require(total <= MAX_BYTES + MAX_MANIFEST && bytes <= limit) { "Archive exceeds the import budget." }
                            check(staging.usableSpace > n + 32L * 1024 * 1024) { "Not enough storage to restore." }
                            out.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(File(staging, "conversation.json").readText())
            require(manifest.getInt("version") == 1) { "Unsupported archive version." }
            val title = manifest.getString("title"); require(title.isNotBlank() && title.length <= 120)
            val engine = manifest.getString("engine"); require(engine.length <= 200)
            val files = manifest.getJSONArray("attachments"); require(files.length() <= 64)
            val renames = mutableMapOf<String, String>()
            for (i in 0 until files.length()) {
                val record = files.getJSONObject(i); val name = record.getString("name")
                require(name.matches(Regex("[a-f0-9-]{36}\\.(jpg|wav)")) && name !in renames)
                val file = File(staging, name); val size = record.getLong("bytes")
                require(size in 1..MAX_FILE && file.isFile && file.length() == size && digest(file) == record.getString("sha256")) { "Attachment integrity check failed." }
                renames[name] = UUID.randomUUID().toString() + "." + name.substringAfterLast('.')
            }
            require(entries == setOf("conversation.json") + renames.keys.map { "media/$it" }) { "Archive contains missing or unlisted files." }
            val messages = manifest.getJSONArray("messages"); require(messages.length() in 2..1000 && messages.length() % 2 == 0)
            val turns = buildList {
                var previous = 0L
                for (i in 0 until messages.length() step 2) {
                    val user = messages.getJSONObject(i); val assistant = messages.getJSONObject(i + 1)
                    val id = user.getLong("turn")
                    require(id > previous && assistant.getLong("turn") == id && user.getString("role") == "user" && assistant.getString("role") == "assistant")
                    previous = id
                    val text = user.getString("text"); val reply = assistant.getString("text")
                    require(text.length <= 8_000 && reply.length <= 32_768)
                    val parts = ConversationRepository.decodeMedia(user.getJSONArray("media")).map { part ->
                        val name = checkNotNull(renames[ConversationRepository.reference(part)]) { "Missing attachment." }
                        when (part) { is RequestPart.Image -> RequestPart.Image(name); is RequestPart.Audio -> RequestPart.Audio(name, part.durationMs); else -> error("Invalid attachment") }
                    }
                    require(assistant.getJSONArray("media").length() == 0)
                    val status = ReplyStatus.valueOf(assistant.getString("status")).let { if (it == ReplyStatus.STREAMING) ReplyStatus.INTERRUPTED else it }
                    add(ProbeTurn.User(id, text, parts.any { it is RequestPart.Image }, parts.any { it is RequestPart.Audio }, parts))
                    add(ProbeTurn.Assistant(id, reply, status, assistant.optJSONObject("receipt")?.let { app.lfm3.probe.online.ToolReceipt.decode(it) }))
                }
            }
            require(turns.filterIsInstance<ProbeTurn.User>().flatMap { it.media }.map { ConversationRepository.reference(it) }.toSet() == renames.values.toSet())
            renames.forEach { (old, new) ->
                val destination = File(media.directory, new)
                check(!destination.exists() && File(staging, old).renameTo(destination)) { "Unable to restore attachment." }
                published += destination
            }
            val chat = SavedConversation(title = title, turns = turns, real = manifest.getBoolean("real"), engine = engine)
            repository.save(chat)
            published.clear() // The committed database now owns the published files.
            chat.id
        } finally { published.forEach { it.delete() }; staging.deleteRecursively() }
    }

    companion object {
        private const val MAX_MANIFEST = 4 * 1024 * 1024
        private const val MAX_FILE = 20L * 1024 * 1024
        private const val MAX_BYTES = 128L * 1024 * 1024
        private fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
