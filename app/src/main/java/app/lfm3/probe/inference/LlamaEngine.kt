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
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.media.AudioPlayback
import app.lfm3.probe.models.ModelPack
import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.models.ModelCapability

/** Owns the one native session. Loading, unloading and generation cannot overlap. */
class LlamaEngine(private val mediaStore: MediaStore? = null) : InferenceEngine {
    private val mutex = Mutex()
    @Volatile var loaded = false
        private set
    @Volatile var generating = false
        private set
    @Volatile private var pack: ModelPack = ModelCatalog.text
    private val playback = AudioPlayback()
    override val descriptor get() = EngineDescriptor("llama.cpp v0.8.0 · ${pack.title} · CPU", pack.capabilities.map { when (it) {
        ModelCapability.TEXT -> Capability.TEXT; ModelCapability.PHOTO -> Capability.IMAGE
        ModelCapability.VOICE_INPUT -> Capability.AUDIO_INPUT; ModelCapability.VOICE_OUTPUT -> Capability.AUDIO_OUTPUT
    } }.toSet(), EngineKind.LLAMA_CPP)
    override fun prepare() = NativeLlama.prepare()
    override fun cancel() { NativeLlama.cancel(); playback.interrupt() }

    suspend fun load(path: String, contextTokens: Int = 1024, projector: String = "", modelPack: ModelPack = ModelCatalog.text) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loaded = false
            NativeLlama.load(path, contextTokens, projector)
            val expected = modelPack.capabilities.sumOf { when (it) {
                ModelCapability.TEXT -> 1; ModelCapability.PHOTO -> 2; ModelCapability.VOICE_INPUT -> 4; ModelCapability.VOICE_OUTPUT -> 8
            } }
            if (NativeLlama.capabilities() != expected) {
                NativeLlama.unload()
                error("Loaded component capabilities do not match this model pack.")
            }
            pack = modelPack; loaded = true
        }
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock { NativeLlama.unload(); loaded = false }
    }

    override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loaded) { "Load the text model in Models first." }
            val media = request.parts.filterNot { it is RequestPart.Text }
            require(media.none { it is RequestPart.Image } || Capability.IMAGE in descriptor.capabilities) { "This pack does not support photos." }
            require(media.none { it is RequestPart.Audio } || Capability.AUDIO_INPUT in descriptor.capabilities) { "This pack does not support recorded voice." }
            generating = true
            try {
                currentCoroutineContext().ensureActive()
                val messages = request.history + ChatMessage("user", request.parts.filterIsInstance<RequestPart.Text>().joinToString("\n") { it.value }, media)
                val references = messages.flatMap { it.media }.map { when (it) {
                    is RequestPart.Image -> it.privateReference; is RequestPart.Audio -> it.privateReference
                    is RequestPart.Text -> error("Invalid media part.")
                } }
                require(references.size <= 8) { "Eight media attachments per session maximum. Start a new chat." }
                val paths = references.map { checkNotNull(mediaStore) { "Private media storage unavailable." }.resolve(it).absolutePath }.toTypedArray()
                val contents = messages.map { (it.text + "\n<__media__>".repeat(it.media.size)).toByteArray(Charsets.UTF_8) }.toTypedArray()
                NativeLlama.begin(contents, messages.map { it.role }.toTypedArray(), request.generationTicket, request.outputLimit, paths, request.speakResponse)
                // Token pieces may split UTF-8 characters. Preserve incomplete bytes until the next piece.
                val decoder = Charsets.UTF_8.newDecoder()
                val bytes = ByteBuffer.allocate(8192)
                val chars = CharBuffer.allocate(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val piece = NativeLlama.next()
                    NativeLlama.audio()?.takeIf { it.isNotEmpty() }?.let { playback.write(it, NativeLlama.audioRate()) }
                    if (piece == null) break
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
                playback.finish()
                emit(InferenceEvent.Completed(request.turnId, NativeLlama.usage()[2] != 0L))
            } catch (e: IllegalStateException) {
                if (e.message == "Response stopped.") throw CancellationException("Response stopped.").apply { initCause(e) }
                throw e
            } finally { playback.release(); generating = false }
        }
    }
}
