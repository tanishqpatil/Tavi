package app.lfm3.probe.inference

import android.content.*
import android.os.*
import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.models.ModelPack
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bounded, death-aware client. A disconnected native process is never replaced with preview output. */
class RemoteLlamaEngine(context: Context, private val onDeath: () -> Unit = {}) : InferenceEngine {
    private val context = context.applicationContext
    private val ids = AtomicLong()
    private val epochs = AtomicLong()
    private val connectMutex = Mutex()
    private val pending = ConcurrentHashMap<Long, Channel<Bundle>>()
    @Volatile private var transport: Messenger? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var connecting: CompletableDeferred<Messenger>? = null
    @Volatile private var activeGeneration = 0L
    @Volatile var loaded = false
        private set
    @Volatile var generating = false
        private set
    @Volatile var remotePid = 0
        private set
    private var pack: ModelPack = ModelCatalog.text
    private var backend = RuntimeBackend.CPU
    override val descriptor get() = InferenceWire.descriptor(pack, backend)
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == InferenceWire.RESPONSE) {
            val data = message.data
            val channel = pending[data.getLong("id")]
            if (channel != null && !channel.trySend(data).isSuccess) lostConnection()
        }
        true
    })

    private fun lostConnection() {
        val hadConnection = connection != null || transport != null
        transport = null; loaded = false; remotePid = 0
        connecting?.completeExceptionally(RuntimeDisconnectedException()); connecting = null
        pending.values.forEach { it.close(RuntimeDisconnectedException()) }; pending.clear()
        val old = connection; connection = null
        if (old != null) Handler(Looper.getMainLooper()).post { runCatching { context.unbindService(old) } }
        if (hadConnection) onDeath()
    }

    private suspend fun connect(): Messenger = connectMutex.withLock {
        transport?.takeIf { it.binder.isBinderAlive }?.let { return@withLock it }
        val deferred = CompletableDeferred<Messenger>()
        withContext(Dispatchers.Main.immediate) {
            connecting = deferred
            val binding = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (connection !== this) return
                    transport = Messenger(binder)
                    deferred.complete(transport!!)
                }
                override fun onServiceDisconnected(name: ComponentName) { if (connection === this) lostConnection() }
                override fun onBindingDied(name: ComponentName) { if (connection === this) lostConnection() }
                override fun onNullBinding(name: ComponentName) { if (connection === this) lostConnection() }
            }
            connection = binding
            if (!context.bindService(Intent(context, InferenceService::class.java), binding, Context.BIND_AUTO_CREATE)) lostConnection()
        }
        try { withTimeout(20_000) { deferred.await() } }
        catch (error: Exception) { lostConnection(); throw error }
        finally { connecting = null }
    }

    private fun send(target: Messenger, what: Int, data: Bundle) {
        try { target.send(Message.obtain(null, what).apply { this.data = data; replyTo = replies }) }
        catch (_: RemoteException) { if (transport?.binder == target.binder) lostConnection(); throw RuntimeDisconnectedException() }
    }

    private suspend fun exchange(kind: String, args: Bundle = Bundle(), timeout: Long = 180_000): Bundle {
        val target = connect()
        val id = ids.incrementAndGet()
        val channel = Channel<Bundle>(1); pending[id] = channel
        args.putLong("id", id); args.putString("kind", kind)
        try {
            send(target, InferenceWire.COMMAND, args)
            val response = withTimeout(timeout) { channel.receive() }
            check(response.getString("kind") == "ok") { response.getString("message") ?: "Runtime operation was interrupted." }
            return response
        } catch (error: TimeoutCancellationException) {
            // A timed-out load may still own native mappings; do not permit deletion or a second load.
            terminateUnresponsive()
            throw RuntimeDisconnectedException()
        } finally { pending.remove(id); channel.close() }
    }
    suspend fun info(): String {
        if (transport == null && !generating) withContext(Dispatchers.IO) {
            InferenceWire.directory(context).listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }?.forEach { it.delete() }
        }
        val result = exchange("info", timeout = 30_000)
        remotePid = result.getInt("pid")
        return checkNotNull(result.getString("info"))
    }
    suspend fun openclAvailable(): Boolean {
        check(!loaded && !generating) { "Unload the model before checking a GPU backend." }
        if (transport == null || remotePid == 0) info()
        return exchange("opencl").getBoolean("available")
    }
    suspend fun load(modelPack: ModelPack, contextTokens: Int, cpu: CpuOptions = CpuOptions(), execution: RuntimeBackend = RuntimeBackend.CPU) {
        check(!loaded && !generating) { "Unload the current model before loading another pack." }
        if (transport == null || remotePid == 0) info()
        exchange("load", Bundle().apply { putString("pack", modelPack.id); putInt("context", contextTokens)
            putInt("cpuThreads", cpu.threads); putInt("prefillThreads", cpu.prefillThreads); putInt("prefillBatch", cpu.batch); putString("backend", execution.name) })
        pack = modelPack; backend = execution; loaded = true
    }
    suspend fun unload() {
        if (transport != null) exchange("unload")
        loaded = false
        withContext(Dispatchers.Main.immediate) {
            val old = connection; connection = null; transport = null; remotePid = 0
            if (old != null) runCatching { context.unbindService(old) }
        }
    }
    override fun prepare(): Long = epochs.incrementAndGet()
    override fun cancel() {
        epochs.incrementAndGet()
        transport?.let { target -> runCatching { send(target, InferenceWire.CANCEL, InferenceWire.data(activeGeneration, "cancel")) } }
    }
    override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) = withContext(Dispatchers.IO) {
        check(loaded) { "Reload the model in Models before sending another message." }
        if (request.generationTicket != epochs.get()) throw CancellationException("Response stopped.")
        check(!generating) { "Previous response is still stopping. Retry shortly." }
        generating = true
        val id = ids.incrementAndGet(); activeGeneration = id
        val channel = Channel<Bundle>(1); pending[id] = channel
        var file: java.io.File? = null
        var submitted = false
        var terminal = false
        try {
            file = InferenceWire.encode(context, request)
            val target = transport ?: throw RuntimeDisconnectedException()
            send(target, InferenceWire.COMMAND, InferenceWire.data(id, "generate").apply { putString("file", file.name) })
            submitted = true
            while (true) {
                val event = withTimeout(180_000) { channel.receive() }
                if (request.generationTicket != epochs.get()) throw CancellationException("Response stopped.")
                when (event.getString("kind")) {
                    "batch" -> {
                        val text = event.getString("text").orEmpty(); require(text.length <= 8192)
                        if (text.isNotEmpty()) emit(InferenceEvent.TextDelta(request.turnId, text))
                        if (event.containsKey("tokens")) emit(InferenceEvent.Usage(request.turnId, event.getInt("tokens"), event.getLong("nanos"),
                            event.getLong("audioSamples"), event.getInt("audioRate"), event.getFloat("audioPeak"),
                            if (event.containsKey("firstAudioMs")) event.getLong("firstAudioMs") else null,
                            event.getLong("prefillNanos"), event.getBoolean("optimizedCpuBuffer")))
                        send(target, InferenceWire.ACK, InferenceWire.data(id, "ack").apply { putLong("sequence", event.getLong("sequence")) })
                    }
                    "done" -> { terminal = true; emit(InferenceEvent.Completed(request.turnId, event.getBoolean("limit"))); break }
                    "cancelled" -> { terminal = true; throw CancellationException("Response stopped.") }
                    "error" -> { terminal = true; error(event.getString("message") ?: "Runtime failed. Reload and retry.") }
                    else -> error("Invalid runtime response.")
                }
            }
        } catch (_: TimeoutCancellationException) {
            terminateUnresponsive(); throw RuntimeDisconnectedException()
        } finally {
            if (submitted && !terminal && transport != null) withContext(NonCancellable) {
                try {
                    val target = transport ?: throw RuntimeDisconnectedException()
                    send(target, InferenceWire.CANCEL, InferenceWire.data(id, "cancel"))
                    withTimeout(5_000) {
                        while (true) {
                            val message = channel.receive()
                            if (message.getString("kind") in setOf("done", "cancelled", "error")) break
                            if (message.getString("kind") == "batch") send(target, InferenceWire.ACK,
                                InferenceWire.data(id, "ack").apply { putLong("sequence", message.getLong("sequence")) })
                        }
                    }
                } catch (_: Exception) { if (transport != null) terminateUnresponsive() }
            }
            pending.remove(id); channel.close(); file?.delete(); generating = false
        }
    }
    private fun terminateUnresponsive() {
        val pid = remotePid
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        lostConnection()
    }
}
