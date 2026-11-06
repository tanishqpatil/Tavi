package app.lfm3.probe.online

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpReply(val status: Int, val type: String, val session: String?, val body: String)
fun interface ToolHttp {
    suspend fun request(uri: URI, body: String?, headers: Map<String, String>): HttpReply
}

/** At most two bounded network tasks; cancelling a turn closes its active socket. No automatic retries. */
class BoundedHttp : ToolHttp {
    override suspend fun request(uri: URI, body: String?, headers: Map<String, String>): HttpReply = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val future = executor.submit {
            try {
                if (!continuation.isActive) return@submit
                val connection = uri.toURL().openConnection() as HttpURLConnection
                active.set(connection)
                if (!continuation.isActive) { connection.disconnect(); return@submit }
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "Tavi/0.1 (+https://github.com/tanishqpatil/Tavi)")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8); require(bytes.size <= 32_768)
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val bytes = ByteArrayOutputStream()
                // Error pages may contain private details: do not expose their bodies or log them.
                if (status in 200..299 && status != 202 && status != 204) {
                    require(connection.contentLengthLong <= MAX_BYTES) { "Online response exceeded its byte budget." }
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        val deadline = System.nanoTime() + 25_000_000_000L
                        while (continuation.isActive) {
                            check(System.nanoTime() < deadline) { "Online response timed out." }
                            val size = input.read(buffer); if (size < 0) break
                            require(bytes.size() + size <= MAX_BYTES) { "Online response exceeded its byte budget." }
                            bytes.write(buffer, 0, size)
                            // Request-scoped SSE may stay open after the response. Stop at its matching
                            // JSON-RPC response, not at an unrelated progress notification.
                            if (connection.contentType.orEmpty().startsWith("text/event-stream") && body != null && bytes.size() > 0) {
                                val id = org.json.JSONObject(body).opt("id")
                                if (id != null && McpClient.sseResponse(bytes.toString("UTF-8"), id) != null) break
                            }
                        }
                    }
                }
                val reply = HttpReply(status, connection.contentType.orEmpty(), connection.getHeaderField("Mcp-Session-Id"), bytes.toString("UTF-8"))
                if (continuation.isActive) continuation.resume(reply)
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            finally { active.getAndSet(null)?.disconnect() }
        }
        continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect(); future.cancel(true) }
    }
    companion object {
        const val MAX_BYTES = 256 * 1024
        private val executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "Tavi-online").apply { isDaemon = true } }
    }
}
