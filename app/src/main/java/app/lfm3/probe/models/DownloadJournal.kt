package app.lfm3.probe.models

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

enum class DownloadStatus { IDLE, QUEUED, RUNNING, RETRY, PAUSING, PAUSED, COMPLETE, FAILED }
data class DownloadRecord(
    val packId: String = "", val identity: String = "", val token: String = "",
    val status: DownloadStatus = DownloadStatus.IDLE, val bytes: Long = 0,
    val unmetered: Boolean = true, val attempts: Int = 0, val message: String = "",
) {
    val active get() = status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.RETRY, DownloadStatus.PAUSING)
}

/** One persistent transfer intent, separate from UI/native process state. No URLs or user content. */
internal class DownloadJournal(file: File) {
    private val file = AtomicFile(file.apply { parentFile?.mkdirs() })
    fun read(): DownloadRecord = try {
        val bytes = file.openRead().use { input ->
            val buffer = ByteArray(4097); var count = 0
            while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
            require(count <= 4096); buffer.copyOf(count)
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8)); require(json.getInt("version") == 1)
        DownloadRecord(json.getString("pack"), json.getString("identity"), json.getString("token"),
            DownloadStatus.valueOf(json.getString("status")), json.getLong("bytes"), json.getBoolean("unmetered"), json.getInt("attempts"), json.getString("message"))
    } catch (_: Exception) { DownloadRecord() }
    fun write(record: DownloadRecord) {
        val bytes = JSONObject().apply {
            put("version", 1); put("pack", record.packId); put("identity", record.identity); put("token", record.token)
            put("status", record.status.name); put("bytes", record.bytes); put("unmetered", record.unmetered)
            put("attempts", record.attempts); put("message", record.message)
        }.toString().toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
