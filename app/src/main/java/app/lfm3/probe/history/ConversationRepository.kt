package app.lfm3.probe.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.lfm3.probe.inference.RequestPart
import app.lfm3.probe.presentation.ProbeTurn
import app.lfm3.probe.presentation.ReplyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ConversationSummary(val id: String, val title: String, val updated: Long, val real: Boolean, val count: Int)
data class SavedConversation(
    val id: String = UUID.randomUUID().toString(), val title: String = "New conversation",
    val turns: List<ProbeTurn> = emptyList(), val real: Boolean = false,
    val engine: String = "deterministic-preview", val draft: String = "", val speak: Boolean = false,
)

/** Private, transactional history. Only the active message page and library page enter UI memory. */
class ConversationRepository(context: Context) {
    private val helper = Database(context)
    private val mutex = Mutex()
    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(helper.writableDatabase) }
    }

    suspend fun recoverInterrupted() = access { db ->
        db.execSQL("UPDATE messages SET status='INTERRUPTED' WHERE status='STREAMING'")
    }

    suspend fun save(chat: SavedConversation) = access { db ->
        require(chat.title.length <= 120 && chat.draft.length <= 8_000)
        db.beginTransaction()
        try {
            val metadata = ContentValues().apply {
                put("id", chat.id); put("title", chat.title); put("updated", System.currentTimeMillis())
                put("real", if (chat.real) 1 else 0); put("engine", chat.engine)
                put("draft", chat.draft); put("speak", if (chat.speak) 1 else 0)
            }
            // Update instead of REPLACE: replacing a parent would cascade-delete its older pages.
            if (db.update("conversations", metadata, "id=?", arrayOf(chat.id)) == 0) db.insertOrThrow("conversations", null, metadata)
            chat.turns.forEach { turn ->
                val values = ContentValues().apply {
                    put("chat", chat.id); put("turn", turn.id)
                    put("role", if (turn is ProbeTurn.User) "user" else "assistant")
                    val text = when (turn) { is ProbeTurn.User -> turn.text; is ProbeTurn.Assistant -> turn.text }
                    require(text.length <= 32_768) { "Message storage limit exceeded." }
                    put("text", text)
                    put("status", if (turn is ProbeTurn.Assistant) turn.status.name else "COMPLETE")
                    put("media", if (turn is ProbeTurn.User) encodeMedia(turn.media).toString() else "[]")
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun list(query: String = "", offset: Int = 0): List<ConversationSummary> = access { db ->
        // LIKE metacharacters are escaped; search never becomes SQL or a wildcard expansion.
        val pattern = "%${query.take(200).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        db.rawQuery("""SELECT c.id,c.title,c.updated,c.real,
            (SELECT COUNT(*) FROM messages m WHERE m.chat=c.id AND role='user') AS count
            FROM conversations c WHERE c.title LIKE ? ESCAPE '\' OR EXISTS
            (SELECT 1 FROM messages m WHERE m.chat=c.id AND m.text LIKE ? ESCAPE '\')
            ORDER BY c.updated DESC,c.id DESC LIMIT 30 OFFSET ?""", arrayOf(pattern, pattern, offset.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ConversationSummary(cursor.string("id"), cursor.string("title"),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated")), cursor.getInt(cursor.getColumnIndexOrThrow("real")) != 0,
                cursor.getInt(cursor.getColumnIndexOrThrow("count")))) }
        }
    }

    suspend fun load(id: String, limit: Int = 40): SavedConversation? = access { db ->
        db.query("conversations", null, "id=?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) return@access null
            val turns = db.rawQuery("""SELECT * FROM messages WHERE chat=? AND turn IN
                (SELECT DISTINCT turn FROM messages WHERE chat=? ORDER BY turn DESC LIMIT ?)
                ORDER BY turn ASC,CASE role WHEN 'user' THEN 0 ELSE 1 END""", arrayOf(id, id, limit.coerceIn(1, 500).toString())).use { m ->
                buildList { while (m.moveToNext()) {
                    val turn = m.getLong(m.getColumnIndexOrThrow("turn")); val text = m.string("text")
                    if (m.string("role") == "user") {
                        val media = decodeMedia(JSONArray(m.string("media")))
                        add(ProbeTurn.User(turn, text, media.any { it is RequestPart.Image }, media.any { it is RequestPart.Audio }, media))
                    } else add(ProbeTurn.Assistant(turn, text, ReplyStatus.valueOf(m.string("status"))))
                } }
            }
            SavedConversation(id, c.string("title"), turns, c.getInt(c.getColumnIndexOrThrow("real")) != 0,
                c.string("engine"), c.string("draft"), c.getInt(c.getColumnIndexOrThrow("speak")) != 0)
        }
    }

    suspend fun count(id: String): Int = access { db ->
        db.rawQuery("SELECT COUNT(*) FROM messages WHERE chat=? AND role='user'", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
    }
    suspend fun rename(id: String, title: String) = access { db ->
        require(title.isNotBlank() && title.length <= 120)
        db.update("conversations", ContentValues().apply { put("title", title.trim()) }, "id=?", arrayOf(id))
    }
    suspend fun delete(id: String) = access { it.delete("conversations", "id=?", arrayOf(id)) }
    suspend fun references(): Set<String> = access { db ->
        db.rawQuery("SELECT media FROM messages WHERE role='user'", null).use { c ->
            buildSet { while (c.moveToNext()) decodeMedia(JSONArray(c.getString(0))).forEach { add(reference(it)) } }
        }
    }

    private class Database(context: Context) : SQLiteOpenHelper(context,
        File(context.noBackupFilesDir, "history/conversations.db").apply { parentFile!!.mkdirs() }.path, null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL,
                real INTEGER NOT NULL,engine TEXT NOT NULL,draft TEXT NOT NULL,speak INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE messages(chat TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                turn INTEGER NOT NULL,role TEXT NOT NULL CHECK(role IN ('user','assistant')),text TEXT NOT NULL,
                status TEXT NOT NULL,media TEXT NOT NULL,PRIMARY KEY(chat,turn,role))""")
            db.execSQL("CREATE INDEX conversations_updated ON conversations(updated DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Never erase conversations as an implicit migration fallback.
            error("Unsupported history schema migration: $oldVersion to $newVersion")
        }
    }

    companion object {
        fun reference(part: RequestPart): String = when (part) {
            is RequestPart.Image -> part.privateReference; is RequestPart.Audio -> part.privateReference
            is RequestPart.Text -> error("Text is not an attachment")
        }
        fun encodeMedia(parts: List<RequestPart>) = JSONArray().apply { parts.forEach { part -> put(JSONObject().apply {
            put("name", reference(part)); put("type", if (part is RequestPart.Image) "image" else "audio")
            if (part is RequestPart.Audio) put("duration", part.durationMs)
        }) } }
        fun decodeMedia(array: JSONArray): List<RequestPart> {
            require(array.length() <= 8)
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index); val name = item.getString("name")
                require(name.matches(Regex("[a-f0-9-]{36}\\.(jpg|wav)"))) { "Invalid media reference" }
                when (item.getString("type")) {
                    "image" -> { require(name.endsWith(".jpg")); RequestPart.Image(name) }
                    "audio" -> { val duration = item.getLong("duration"); require(name.endsWith(".wav") && duration in 1..30_000); RequestPart.Audio(name, duration) }
                    else -> error("Unsupported attachment type")
                }
            }
        }
        private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
    }
}
