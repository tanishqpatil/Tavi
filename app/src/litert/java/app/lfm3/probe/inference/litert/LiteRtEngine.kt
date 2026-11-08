package app.lfm3.probe.inference.litert

import android.content.Context
import app.lfm3.probe.inference.*
import app.lfm3.probe.media.AudioPlayback
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.models.ModelPack
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor

/** Experimental CPU CompiledModel adapter. Graph creation/run/close stay on one owned worker. */
internal class LiteRtEngine(private val context: Context) : RuntimeSession {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tavi-litert") }.asCoroutineDispatcher()
    private val media = MediaStore(context)
    private val cancelled = AtomicBoolean()
    private val epoch = AtomicLong()
    @Volatile private var operation: Job? = null
    @Volatile override var loaded = false
        private set
    private var session: LiteRtSession? = null
    private var pack = ModelCatalog.get("lfm3-omni-litert")
    private var contextLimit = 1024
    private val playback = AudioPlayback(context) { cancel() }
    override val descriptor get() = InferenceWire.descriptor(pack)
    override fun prepare(): Long { cancelled.set(false); return epoch.incrementAndGet() }
    override fun cancel() { epoch.incrementAndGet(); cancelled.set(true); playback.interrupt() }
    private fun checkActive() {
        operation?.ensureActive()
        if (cancelled.get()) throw CancellationException("Response stopped.")
    }

    override suspend fun load(pack: ModelPack, directory: File, context: Int, cpu: CpuOptions, backend: RuntimeBackend) = withContext(worker) {
        check(!loaded)
        check(backend == RuntimeBackend.CPU) { "The LiteRT prototype supports CPU only. Select CPU and load again." }
        check(context in listOf(1024, 2048, 4096)) { "This LiteRT conversion has a fixed 4096-token allocation. Select at most 4096 context." }
        check(NativeLlama.liteRtCpuCompatible()) { "LiteRT is unavailable on this CPU profile: SME2 is advertised without SME. Use a GGUF pack." }
        cancelled.set(false); operation = currentCoroutineContext()[Job]
        try {
            session = LiteRtSession.open(this@LiteRtEngine.context, pack, directory, cpu, ::checkActive)
            checkActive()
            this@LiteRtEngine.pack = pack; contextLimit = context; loaded = true
        } catch (error: Throwable) { session?.close(); session = null; throw error }
        finally { operation = null }
    }

    override suspend fun unload() = withContext(worker) {
        playback.release()
        try { session?.close() } finally { session = null; loaded = false }
        Unit
    }
    override suspend fun dispose() { try { unload() } finally { worker.close() } }

    override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) = withContext(worker) {
        check(loaded) { "Load the LiteRT model first." }
        check(!request.routingJson) { "Tool routing is not supported by the LiteRT prototype." }
        require(request.outputLimit in 1..contextLimit / 2)
        val live = checkNotNull(session)
        val activeContext = currentCoroutineContext()
        operation = activeContext[Job]
        fun <T> bridge(block: suspend () -> T): T = runBlocking(activeContext.minusKey(ContinuationInterceptor)) { block() }
        fun checkTicket() { checkActive(); if (request.generationTicket != epoch.get()) throw CancellationException("Response stopped.") }
        checkTicket()
        var tokenCount = 0; var samples = 0L; var peak = 0f; var firstAudio: Long? = null
        var decodeStarted = 0L; var prefillNanos = 0L; var limited = false
        val started = System.nanoTime()
        val textIds = ArrayList<Int>(); var emitted = ""
        fun usage() = bridge { emit(InferenceEvent.Usage(request.turnId, tokenCount,
            if (decodeStarted == 0L) 0 else System.nanoTime() - decodeStarted,
            samples, if (samples > 0) 24000 else 0, peak, firstAudio, prefillNanos)) }
        try {
            playback.prepare()
            val speak = request.speakResponse
            val instruction = request.systemInstruction.ifEmpty { if (speak) "Respond with interleaved text and audio." else "" }
            val messages = (if (instruction.isNotEmpty()) listOf(ChatMessage("system", instruction)) else emptyList()) +
                request.history + ChatMessage("user", request.parts.filterIsInstance<RequestPart.Text>().joinToString("\n") { it.value }, request.parts.filterNot { it is RequestPart.Text })
            require(messages.sumOf { it.media.size } <= 8) { "Eight media attachments per session maximum. Start a new chat." }
            val rows = ArrayList<FloatArray>()
            fun admit(n: Int) { check(rows.size + n + request.outputLimit <= contextLimit) { "Media and output exceed context. Increase context, shorten audio, or start a new chat." } }
            fun text(value: String) {
                val ids = live.tokenizer.encode(value); admit(ids.size)
                ids.forEach { checkTicket(); rows.add(live.lm.embedToken(it)) }
            }
            fun features(values: List<FloatArray>) { admit(values.size); rows.addAll(values) }
            text("<|startoftext|>")
            for (message in messages) {
                require(message.role in setOf("system", "user", "assistant"))
                text("<|im_start|>${message.role}\n${message.text}")
                for (part in message.media) {
                    checkTicket()
                    // Typed media parts supply their own rows; literal placeholder text never consumes attachments.
                    text("<|vision_start|>")
                    features(when (part) {
                        is RequestPart.Image -> live.vision.encode(LiteRtMedia.photo(media.resolve(part.privateReference)))
                        is RequestPart.Audio -> live.audio.encode(LiteRtMedia.audio(media.resolve(part.privateReference)))
                        is RequestPart.Text -> error("Invalid media part.")
                    })
                    text("<|vision_end|>")
                }
                text("<|im_end|>\n")
            }
            text("<|im_start|>assistant\n<think></think>${if (speak) "<|mixed_start|>" else ""}")
            live.codec.reset()
            live.codec.sink = { pcm ->
                checkTicket()
                require(pcm.all { it.isFinite() }) { "Speech decoder produced invalid samples." }
                val remaining = (30L * 24000 - samples).coerceAtLeast(0).toInt()
                val chunk = if (pcm.size > remaining) pcm.copyOf(remaining) else pcm
                if (chunk.isNotEmpty()) {
                    if (firstAudio == null) firstAudio = (System.nanoTime() - started) / 1_000_000
                    samples += chunk.size; peak = maxOf(peak, chunk.maxOf { kotlin.math.abs(it) })
                    bridge { playback.write(chunk, 24000) }; usage()
                }
                if (samples >= 30L * 24000) limited = true
            }
            val result = live.generator.generate(rows, if (speak) SpeechGenerator.Mode.INTERLEAVED else SpeechGenerator.Mode.TEXT,
                !speak, contextLimit - rows.size, Sampling(), Rng(request.turnId),
                listener = object : SpeechGenerator.Listener {
                    override fun onToken(token: Int) {
                        checkTicket()
                        if (decodeStarted == 0L) { decodeStarted = System.nanoTime(); prefillNanos = decodeStarted - started }
                        tokenCount++; textIds.add(token)
                        // Hold incomplete trailing UTF-8 until a later token completes the character.
                        val value = live.tokenizer.decode(textIds).trimEnd('\uFFFD')
                        check(value.startsWith(emitted)) { "Invalid incremental text from LiteRT." }
                        if (value.length > emitted.length) bridge { emit(InferenceEvent.TextDelta(request.turnId, value.substring(emitted.length))) }
                        emitted = value; usage()
                    }
                    override fun onFrame(codes: IntArray) {
                        checkTicket()
                        if (decodeStarted == 0L) { decodeStarted = System.nanoTime(); prefillNanos = decodeStarted - started }
                        live.codec.push(codes)
                    }
                }, stop = {
                    checkTicket()
                    if (tokenCount >= request.outputLimit || samples >= 30L * 24000) { limited = true; true } else false
                })
            checkTicket()
            if (speak && samples < 30L * 24000) live.codec.finish()
            val finalText = live.tokenizer.decode(textIds)
            if (finalText.startsWith(emitted) && finalText.length > emitted.length) emit(InferenceEvent.TextDelta(request.turnId, finalText.substring(emitted.length)))
            playback.finish(); usage()
            emit(InferenceEvent.Completed(request.turnId, limited || (!result.finished && !result.stopped)))
        } finally { live.codec.sink = {}; playback.release(); operation = null }
    }
}
