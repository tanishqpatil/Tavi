package app.lfm3.probe.models

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Foreground-only P1 installer. Interrupted transfers retain immutable partial files for retry. */
class PackInstaller(private val root: File, private val log: (String) -> Unit = {}) {
    @Volatile private var connection: HttpsURLConnection? = null
    val installed = File(root, "text-${TextPack.revision}")
    private val staging = File(root, "text-${TextPack.revision}.staging")
    fun interrupt() { connection?.disconnect() }

    fun verifyInstalled(): Boolean = installed.isDirectory && runCatching {
        TextPack.artifacts.forEach { it.verify(File(installed, it.name)) }
    }.isSuccess

    suspend fun install(progress: (Long, Long) -> Unit) {
        root.mkdirs(); staging.mkdirs()
        val remaining = TextPack.artifacts.sumOf { (it.bytes - File(staging, it.name).length()).coerceAtLeast(0) }
        check(root.usableSpace >= remaining + 64L * 1024 * 1024) { "Not enough free storage for this model." }
        for (artifact in TextPack.artifacts) {
            currentCoroutineContext().ensureActive()
            val file = File(staging, artifact.name)
            if (file.length() > artifact.bytes) check(file.delete())
            if (file.length() < artifact.bytes) download(artifact, file) { count, total ->
                if (artifact == TextPack.weights) progress(count, total)
            }
            currentCoroutineContext().ensureActive()
            try { artifact.verify(file) } catch (e: Exception) { file.delete(); throw e }
            log("ARTIFACT_SHA256_VERIFIED")
        }
        currentCoroutineContext().ensureActive()
        // Same-filesystem directory rename publishes only a complete, verified component set.
        check(!installed.exists()) { "A model version is already installed." }
        check(staging.renameTo(installed)) { "Unable to activate verified model files." }
        log("MODEL_PACK_ACTIVATED")
    }

    fun remove() {
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
                val conn = url.openConnection() as HttpsURLConnection
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
                check(code == 200 || code == 206) { "Model host returned HTTP $code. Retry later." }
                log("DOWNLOAD_HTTP_${code}_OFFSET_$offset")
                if (code == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(conn.getHeaderField("Content-Range") ?: "") ?: error("Invalid resume response.")
                    check(range.groupValues[1].toLong() == offset && range.groupValues[3].toLong() == artifact.bytes && range.groupValues[2].toLong() == artifact.bytes - 1) { "Resume range mismatch." }
                } else offset = 0 // A host may ignore Range. Restart; never append a complete response.
                val length = conn.contentLengthLong
                check(length < 0 || length == artifact.bytes - offset) { "Unexpected download size." }
                RandomAccessFile(file, "rw").use { output ->
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
                    output.fd.sync()
                }
                break
            }
        } finally { connection?.disconnect(); connection = null }
    }

    private fun allowedHost(host: String): Boolean = host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.co")
}
