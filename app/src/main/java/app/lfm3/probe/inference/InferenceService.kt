package app.lfm3.probe.inference

import android.app.Service
import android.content.Intent
import android.os.*
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.models.PackInstaller
import app.lfm3.probe.models.PackLease
import kotlinx.coroutines.*
import java.io.File

/** Crash boundary: this is the only production process that loads the JNI library or model. */
class InferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine by lazy { LlamaEngine(MediaStore(this)) }
    @Volatile private var initialized = false
    private var lease: PackLease? = null
    private var active: Job? = null
    @Volatile private var activeId = 0L
    private var acknowledgement: Pair<Long, CompletableDeferred<Unit>>? = null
    private val handler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            InferenceWire.CANCEL -> if (message.data.getLong("id") == activeId) cancelActive()
            InferenceWire.ACK -> acknowledgement?.takeIf { it.first == message.data.getLong("sequence") && message.data.getLong("id") == activeId }?.second?.complete(Unit)
            InferenceWire.COMMAND -> command(message)
        }
        true
    }
    private val messenger = Messenger(handler)
    override fun onBind(intent: Intent): IBinder = messenger.binder
    private fun cancelActive() {
        if (initialized) engine.cancel()
        active?.cancel(); acknowledgement?.second?.cancel()
    }
    private fun command(message: Message) {
        val reply = message.replyTo ?: return
        val args = message.data
        val id = args.getLong("id")
        val op = args.getString("kind") ?: return
        if (active?.isActive == true) { respond(reply, InferenceWire.data(id, "error").apply { putString("message", "Runtime is still stopping its previous operation. Retry shortly.") }); return }
        activeId = id
        var terminal = InferenceWire.data(id, "error").apply { putString("message", "Runtime ended without a terminal response.") }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                when (op) {
                    "info" -> {
                        val info = NativeLlama.info(); initialized = true
                        terminal = InferenceWire.data(id, "ok").apply { putString("info", info); putInt("pid", Process.myPid()) }
                    }
                    "load" -> {
                        check(initialized) { "Runtime is not initialized." }
                        check(!engine.loaded) { "Unload the active model before loading another pack." }
                        val pack = ModelCatalog.get(checkNotNull(args.getString("pack")))
                        val context = args.getInt("context"); require(context in GenerationLimits.contexts)
                        val cpu = CpuOptions(args.getInt("cpuThreads", 4), args.getInt("prefillThreads", 4), args.getInt("prefillBatch", 128))
                        val backend = RuntimeBackend.valueOf(args.getString("backend") ?: RuntimeBackend.CPU.name)
                        val root = File(noBackupFilesDir, "models")
                        val installer = PackInstaller(root, pack = pack)
                        lease = PackLease.acquire(root, pack, shared = true)
                        try {
                            check(installer.verifyInstalled()) { "Model pack failed verification. Remove and download it again." }
                            ensureActive()
                            engine.load(File(installer.installed, pack.weights.name).path, context,
                                pack.projector?.let { File(installer.installed, it.name).path }.orEmpty(), pack, cpu, backend)
                        } catch (error: Exception) { lease?.close(); lease = null; throw error }
                        terminal = InferenceWire.data(id, "ok")
                    }
                    "opencl" -> {
                        check(initialized && !engine.loaded) { "Unload the model before checking OpenCL." }
                        terminal = InferenceWire.data(id, "ok").apply { putBoolean("available", NativeLlama.openclAvailable()) }
                    }
                    "unload" -> { if (initialized) engine.unload(); lease?.close(); lease = null; terminal = InferenceWire.data(id, "ok") }
                    "generate" -> {
                        check(engine.loaded) { "Load a model before sending a message." }
                        val file = InferenceWire.requestFile(this@InferenceService, checkNotNull(args.getString("file")))
                        val request = try { ensureActive(); InferenceWire.decode(file, engine.prepare()) } finally { file.delete() }
                        var sequence = 0L
                        var text = StringBuilder()
                        var usage: InferenceEvent.Usage? = null
                        var last = System.nanoTime()
                        suspend fun flush() {
                            if (text.isEmpty() && usage == null) return
                            val ack = CompletableDeferred<Unit>()
                            val serial = ++sequence
                            // One unacknowledged batch at a time bounds the receiving queue.
                            withContext(Dispatchers.Main) { acknowledgement = serial to ack }
                            respond(reply, InferenceWire.data(id, "batch").apply {
                                putLong("sequence", serial); putString("text", text.toString()); putLong("turn", request.turnId)
                                usage?.let {
                                    putInt("tokens", it.outputTokens); putLong("nanos", it.decodeNanos)
                                    putLong("audioSamples", it.audioSamples); putInt("audioRate", it.audioRate); putFloat("audioPeak", it.audioPeak)
                                    putLong("prefillNanos", it.prefillNanos); putBoolean("optimizedCpuBuffer", it.optimizedCpuBuffer)
                                    it.firstAudioMs?.let { first -> putLong("firstAudioMs", first) }
                                }
                            })
                            withTimeout(15_000) { ack.await() }
                            text = StringBuilder(); usage = null; last = System.nanoTime()
                        }
                        engine.generate(request) { event -> when (event) {
                            is InferenceEvent.TextDelta -> { text.append(event.value); if (text.length >= 1024 || System.nanoTime() - last >= 40_000_000) flush() }
                            is InferenceEvent.Usage -> { usage = event; if (System.nanoTime() - last >= 250_000_000) flush() }
                            is InferenceEvent.Completed -> { flush(); terminal = InferenceWire.data(id, "done").apply { putBoolean("limit", event.outputLimitReached) } }
                            is InferenceEvent.Failed -> error(event.reason)
                        } }
                    }
                    else -> error("Unknown runtime operation.")
                }
            } catch (_: CancellationException) {
                terminal = InferenceWire.data(id, "cancelled")
            } catch (e: Exception) {
                val safe = (e as? IllegalStateException)?.message?.takeIf { it.length <= 250 && '/' !in it } ?: "Runtime operation failed. Reload the model and retry."
                terminal = InferenceWire.data(id, "error").apply { putString("message", safe) }
            }
        }
        active = job
        job.invokeOnCompletion {
            handler.post {
                if (activeId == id) { active = null; acknowledgement = null }
                runCatching { respond(reply, terminal) }
            }
        }
        job.start()
    }
    private fun respond(reply: Messenger, data: Bundle) { reply.send(Message.obtain(null, InferenceWire.RESPONSE).apply { this.data = data }) }
    override fun onUnbind(intent: Intent): Boolean { cancelActive(); return false }
    override fun onDestroy() {
        cancelActive(); scope.cancel()
        // No clients remain. Process teardown also releases mappings/audio if native code is stuck.
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }
}
