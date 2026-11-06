package app.lfm3.probe.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer

/** Owns the one native session. Loading, unloading and generation cannot overlap. */
class LlamaEngine : InferenceEngine {
    private val mutex = Mutex()
    @Volatile var loaded = false
        private set
    @Volatile var generating = false
        private set
    override val descriptor = EngineDescriptor("llama.cpp v0.8.0 · ARM64 CPU", setOf(Capability.TEXT), EngineKind.LLAMA_CPP)
    override fun prepare() = NativeLlama.prepare()
    override fun cancel() = NativeLlama.cancel()

    suspend fun load(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock { loaded = false; NativeLlama.load(path); loaded = true }
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock { NativeLlama.unload(); loaded = false }
    }

    override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loaded) { "Load the text model in Models first." }
            require(request.parts.all { it is RequestPart.Text }) { "Photo and voice are not enabled in the text adapter." }
            generating = true
            try {
                currentCoroutineContext().ensureActive()
                val messages = request.history + ChatMessage("user", request.parts.filterIsInstance<RequestPart.Text>().joinToString("\n") { it.value })
                NativeLlama.begin(messages.map { it.text.toByteArray(Charsets.UTF_8) }.toTypedArray(), messages.map { it.role }.toTypedArray(), request.generationTicket)
                // Token pieces may split UTF-8 characters. Preserve incomplete bytes until the next piece.
                val decoder = Charsets.UTF_8.newDecoder()
                val bytes = ByteBuffer.allocate(8192)
                val chars = CharBuffer.allocate(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val piece = NativeLlama.next() ?: break
                    bytes.put(piece); bytes.flip(); chars.clear()
                    val result = decoder.decode(bytes, chars, false)
                    check(!result.isError) { "Invalid UTF-8 output from runtime." }
                    bytes.compact(); chars.flip()
                    val text = chars.toString()
                    if (text.isNotEmpty()) emit(InferenceEvent.TextDelta(request.turnId, text))
                    val stats = NativeLlama.usage()
                    emit(InferenceEvent.Usage(request.turnId, stats[0].toInt(), stats[1]))
                }
                currentCoroutineContext().ensureActive()
                emit(InferenceEvent.Completed(request.turnId, NativeLlama.usage()[0] >= 128))
            } catch (e: IllegalStateException) {
                if (e.message == "Response stopped.") throw CancellationException("Response stopped.").apply { initCause(e) }
                throw e
            } finally { generating = false }
        }
    }
}
