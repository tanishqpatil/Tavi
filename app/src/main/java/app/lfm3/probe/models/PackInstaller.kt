package app.lfm3.probe.models

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Scheduler-independent verified installer. Interrupted transfers retain immutable partial files. */
class PackInstaller(private val root: File, private val log: (String) -> Unit = {}, val pack: ModelPack = ModelCatalog.text,
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    @Volatile private var connection: HttpsURLConnection? = null
    val installed = File(root, pack.directory)
    private val staging = File(root, "${pack.directory}.staging")
    fun interrupt() { connection?.disconnect() }
    fun partialBytes(): Long = pack.artifacts.sumOf { File(if (installed.exists()) installed else staging, it.name).length().coerceAtMost(it.bytes) }

    fun verifyInstalled(): Boolean = installed.isDirectory && runCatching {
        pack.artifacts.forEach { it.verify(File(installed, it.name)) }
    }.isSuccess

    suspend fun install(progress: (Long, Long) -> Unit) {
        PackLease.acquire(root, pack, shared = false).use { installLocked(progress) }
    }
    private suspend fun installLocked(progress: (Long, Long) -> Unit) {
        root.mkdirs(); staging.mkdirs()
        val remaining = pack.artifacts.sumOf { (it.bytes - File(staging, it.name).length()).coerceAtLeast(0) }
        check(root.usableSpace >= remaining + pack.workingBytes + 64L * 1024 * 1024) { "Not enough free storage for this model and its runtime caches." }
        var completedBytes = 0L
        for (artifact in pack.artifacts) {
            currentCoroutineContext().ensureActive()
            val file = File(staging, artifact.name)
            if (file.length() > artifact.bytes) check(file.delete())
            if (file.length() < artifact.bytes) download(artifact, file) { count, total ->
                progress(completedBytes + count, pack.bytes)
            }
            currentCoroutineContext().ensureActive()
            try { artifact.verify(file) } catch (e: Exception) { file.delete(); throw e }
            log("ARTIFACT_SHA256_VERIFIED")
            completedBytes += artifact.bytes
            progress(completedBytes, pack.bytes)
        }
        currentCoroutineContext().ensureActive()
        // Same-filesystem directory rename publishes only a complete, verified component set.
        check(!installed.exists()) { "A model version is already installed." }
        check(staging.renameTo(installed)) { "Unable to activate verified model files." }
        log("MODEL_PACK_ACTIVATED")
    }

    fun remove() {
        PackLease.acquire(root, pack, shared = false).use { removeLocked() }
    }
    private fun removeLocked() {
        check(!installed.exists() || installed.deleteRecursively()) { "Unable to remove installed model." }
        check(!staging.exists() || staging.deleteRecursively()) { "Unable to remove partial model." }
    }

    private suspend fun download(artifact: VerifiedArtifact, file: File, progress: (Long, Long) -> Unit) {
        var url = URL(artifact.url)
        var offset = file.length()
        try {
            var hops = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                check(url.protocol == "https" && allowedHost(url.host)) { "Unapproved download redirect." }
                val conn = open(url)
                connection = conn
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 20_000
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
                val code = conn.responseCode
                if (code in listOf(301,302,303,307,308)) {
                    val location = conn.getHeaderField("Location") ?: error("Missing download redirect.")
                    conn.disconnect(); check(++hops <= 6) { "Too many download redirects." }
                    url = URL(url, location); continue
                }
                if (code != 200 && code != 206) throw DownloadHttpException(code)
                log("DOWNLOAD_HTTP_${code}_OFFSET_$offset")
                if (code == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(conn.getHeaderField("Content-Range") ?: "") ?: error("Invalid resume response.")
                    check(range.groupValues[1].toLong() == offset && range.groupValues[3].toLong() == artifact.bytes && range.groupValues[2].toLong() == artifact.bytes - 1) { "Resume range mismatch." }
                } else offset = 0 // A host may ignore Range. Restart; never append a complete response.
                val length = conn.contentLengthLong
                check(length < 0 || length == artifact.bytes - offset) { "Unexpected download size." }
                RandomAccessFile(file, "rw").use { output ->
                    try {
                    output.setLength(offset); output.seek(offset)
                    conn.inputStream.buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var reportedAt = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            check(offset + n <= artifact.bytes) { "Download exceeds catalog size." }
                            check(root.usableSpace > n + 64L * 1024 * 1024) { "Not enough storage to continue." }
                            output.write(buffer, 0, n); offset += n
                            if (System.nanoTime() - reportedAt > 200_000_000) { progress(offset, artifact.bytes); reportedAt = System.nanoTime() }
                        }
                    }
                    } finally { output.fd.sync() }
                }
                if (offset != artifact.bytes) throw java.io.EOFException("Transfer ended before the artifact was complete.")
                break
            }
        } finally { connection?.disconnect(); connection = null }
    }

    private fun allowedHost(host: String): Boolean = host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.co")
}

internal class DownloadHttpException(val code: Int) : java.io.IOException("Model host returned HTTP $code.") {
    val retryable get() = code == 408 || code == 429 || code in 500..599
}
