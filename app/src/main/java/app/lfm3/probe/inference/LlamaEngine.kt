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
internal class LlamaEngine(private val mediaStore: MediaStore? = null) : RuntimeSession {
    private val mutex = Mutex()
    @Volatile override var loaded = false
        private set
    @Volatile var generating = false
        private set
    @Volatile private var pack: ModelPack = ModelCatalog.text
    @Volatile private var backend = RuntimeBackend.CPU
    private val playback = AudioPlayback(mediaStore?.applicationContext) { NativeLlama.cancel() }
    override val descriptor get() = InferenceWire.descriptor(pack, backend)
    override fun prepare() = NativeLlama.prepare()
    override fun cancel() { NativeLlama.cancel(); playback.interrupt() }

    override suspend fun load(pack: ModelPack, directory: java.io.File, context: Int, cpu: CpuOptions, backend: RuntimeBackend) =
        load(java.io.File(directory, pack.weights.name).path, context,
            pack.projector?.let { java.io.File(directory, it.name).path }.orEmpty(), pack, cpu, backend)

    suspend fun load(path: String, contextTokens: Int = 1024, projector: String = "", modelPack: ModelPack = ModelCatalog.text, cpu: CpuOptions = CpuOptions(), execution: RuntimeBackend = RuntimeBackend.CPU) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loaded = false
            val cache = mediaStore?.applicationContext?.let { java.io.File(it.noBackupFilesDir, "runtime-cache/opencl-v080").apply { mkdirs() }.path }.orEmpty()
            NativeLlama.load(path, contextTokens, projector, cpu.threads, cpu.prefillThreads, cpu.batch, execution == RuntimeBackend.OPENCL, cache)
            val expected = modelPack.capabilities.sumOf { when (it) {
                ModelCapability.TEXT -> 1; ModelCapability.PHOTO -> 2; ModelCapability.VOICE_INPUT -> 4; ModelCapability.VOICE_OUTPUT -> 8
            } }
            if (NativeLlama.capabilities() != expected) {
                NativeLlama.unload()
                error("Loaded component capabilities do not match this model pack.")
            }
            pack = modelPack; backend = execution; loaded = true
        }
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
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
                playback.prepare()
                val started = System.nanoTime()
                var audioSamples = 0L
                var audioRate = 0
                var audioPeak = 0f
                var firstAudioMs: Long? = null
                suspend fun usage() {
                    val stats = NativeLlama.usage()
                    emit(InferenceEvent.Usage(request.turnId, stats[0].toInt(), stats[1], audioSamples, audioRate, audioPeak, firstAudioMs, stats[3], stats[4] != 0L))
                }
                currentCoroutineContext().ensureActive()
                // LiquidAI's voice-chat contract asks for mixed output through both the prompt and codec mode.
                val speak = request.speakResponse && Capability.AUDIO_OUTPUT in descriptor.capabilities
                val instruction = request.systemInstruction.ifEmpty {
                    if (speak) "Respond with interleaved text and audio." else ""
                }
                val messages = (if (instruction.isNotEmpty()) listOf(ChatMessage("system", instruction)) else emptyList()) + request.history + ChatMessage("user", request.parts.filterIsInstance<RequestPart.Text>().joinToString("\n") { it.value }, media)
                val references = messages.flatMap { it.media }.map { when (it) {
                    is RequestPart.Image -> it.privateReference; is RequestPart.Audio -> it.privateReference
                    is RequestPart.Text -> error("Invalid media part.")
                } }
                require(references.size <= 8) { "Eight media attachments per session maximum. Start a new chat." }
                val paths = references.map { checkNotNull(mediaStore) { "Private media storage unavailable." }.resolve(it).absolutePath }.toTypedArray()
                val contents = messages.map { (it.text + "\n<__media__>".repeat(it.media.size)).toByteArray(Charsets.UTF_8) }.toTypedArray()
                NativeLlama.begin(contents, messages.map { it.role }.toTypedArray(), request.generationTicket, request.outputLimit, paths, speak, request.routingJson)
                // Token pieces may split UTF-8 characters. Preserve incomplete bytes until the next piece.
                val decoder = Charsets.UTF_8.newDecoder()
                val bytes = ByteBuffer.allocate(8192)
                val chars = CharBuffer.allocate(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val piece = NativeLlama.next()
                    NativeLlama.audio()?.takeIf { it.isNotEmpty() }?.let { pcm ->
                        if (firstAudioMs == null) firstAudioMs = (System.nanoTime() - started) / 1_000_000
                        audioRate = NativeLlama.audioRate()
                        audioSamples += pcm.size
                        audioPeak = maxOf(audioPeak, pcm.maxOf { kotlin.math.abs(it) })
                        playback.write(pcm, audioRate)
                    }
                    if (piece == null) break
                    bytes.put(piece); bytes.flip(); chars.clear()
                    val result = decoder.decode(bytes, chars, false)
                    check(!result.isError) { "Invalid UTF-8 output from runtime." }
                    bytes.compact(); chars.flip()
                    val text = chars.toString()
                    if (text.isNotEmpty()) emit(InferenceEvent.TextDelta(request.turnId, text))
                    usage()
                }
                currentCoroutineContext().ensureActive()
                playback.finish()
                usage() // Includes the final codec tail flushed by the terminal native step.
                emit(InferenceEvent.Completed(request.turnId, NativeLlama.usage()[2] != 0L))
            } catch (e: IllegalStateException) {
                if (e.message == "Response stopped.") throw CancellationException("Response stopped.").apply { initCause(e) }
                throw e
            } finally { playback.release(); generating = false }
        }
    }
}
