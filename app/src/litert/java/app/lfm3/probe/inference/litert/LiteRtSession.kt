package app.lfm3.probe.inference.litert

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.lfm3.probe.inference.CpuOptions
import app.lfm3.probe.models.ModelPack
import com.google.ai.edge.litert.Environment
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/** Owns one conversion's graphs, tokenizer, buffers and environment, on the engine's worker. */
internal class LiteRtSession private constructor(
    val tokenizer: Lfm3Tokenizer,
    val lm: LmRunner,
    val depth: DepthDecoder,
    val frame: FrameEmbed,
    val codec: CodecDecoder,
    val audio: AudioEncoder,
    val vision: VisionEncoder,
    private val resources: NativeResources,
) : AutoCloseable {
    val generator = SpeechGenerator(lm, depth, frame)
    override fun close() { codec.sink = {}; codec.close(); lm.close(); resources.close() }

    companion object {
        fun open(context: Context, pack: ModelPack, directory: File, cpu: CpuOptions, checkActive: () -> Unit): LiteRtSession {
            val manifest = JSONObject(context.assets.open("litert-bundle-sections.json").bufferedReader().use { it.readText() })
            check(pack.weights.sha256 == manifest.getString("bundle_sha256")) { "LiteRT bundle does not match its graph map." }
            fun gate(bytes: Long) {
                checkActive()
                val info = ActivityManager.MemoryInfo()
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
                check(!info.lowMemory && info.availMem >= bytes + 500_000_000) { "Not enough free RAM for this experimental LiteRT pack. Close other apps or use GGUF." }
            }
            gate(1_200_000_000)
            val derived = File(directory, "derived").apply { check(isDirectory || mkdirs()) }
            val existing = derived.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            check(directory.usableSpace + existing >= pack.workingBytes) { "Not enough free storage to prepare LiteRT graphs and caches." }
            val bundle = File(directory, pack.weights.name)
            val sections = manifest.getJSONObject("sections")
            val files = listOf("decoder", "embedder", "audio", "vision_encoder", "vision_adapter").associateWith { name ->
                val section = sections.getJSONObject(name)
                val output = File(derived, "$name.tflite")
                val size = section.getLong("end") - section.getLong("begin")
                val expected = section.getString("sha256")
                if (!output.isFile || output.length() != size || hash(output, checkActive) != expected) {
                    val temporary = File(derived, "$name.tmp")
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        RandomAccessFile(bundle, "r").use { input ->
                            input.seek(section.getLong("begin"))
                            temporary.outputStream().use { out ->
                                var left = size
                                val buffer = ByteArray(256 * 1024)
                                while (left > 0) {
                                    checkActive()
                                    val n = minOf(left, buffer.size.toLong()).toInt()
                                    input.readFully(buffer, 0, n); digest.update(buffer, 0, n); out.write(buffer, 0, n); left -= n
                                }
                                out.fd.sync()
                            }
                        }
                        check(hex(digest.digest()) == expected) { "LiteRT graph section failed verification." }
                        check(temporary.renameTo(output)) { "Unable to activate a LiteRT graph." }
                    } finally { temporary.delete() }
                }
                checkActive()
                output
            }
            val tok = sections.getJSONObject("tokenizer")
            val bytes = RandomAccessFile(bundle, "r").use { input ->
                input.seek(tok.getLong("begin"))
                ByteArray((tok.getLong("end") - tok.getLong("begin")).toInt()).also { input.readFully(it) }
            }
            check(hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == tok.getString("sha256")) { "LiteRT tokenizer failed verification." }
            val expandedSize = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
            check(expandedSize in 1..64_000_000)
            val inflated = java.io.ByteArrayOutputStream()
            InflaterInputStream(bytes.inputStream(8, bytes.size - 8)).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive(); val n = input.read(buffer); if (n < 0) break
                    check(inflated.size().toLong() + n <= expandedSize); inflated.write(buffer, 0, n)
                }
            }
            check(inflated.size().toLong() == expandedSize)
            val tokenizer = Lfm3Tokenizer.load(inflated.toByteArray().inputStream().reader())
            // Cache identity includes SDK/OS/ABI; old profiles are deleted only while no graph is loaded.
            val cacheName = "xnnpack-2.3.0-${Build.SUPPORTED_ABIS.first()}-${Build.FINGERPRINT.hashCode()}"
            derived.listFiles()?.filter { it.isDirectory && it.name.startsWith("xnnpack-") && it.name != cacheName }
                ?.forEach { check(it.deleteRecursively()) }
            val cache = File(derived, cacheName).apply { check(isDirectory || mkdirs()) }
            val resources = NativeResources()
            try {
                val env = resources.own(Environment.create())
                val response = Accel(cpu.threads)
                val media = Accel(cpu.prefillThreads)
                val signatures = manifest.getJSONObject("decoder_signatures")
                val prefill = signatures.getJSONArray("prefill")
                val states = signatures.getJSONArray("state")
                val decoder = files.getValue("decoder"); val embedder = files.getValue("embedder")
                val lm = LmRunner(decoder, embedder, response.cached(cache, decoder, "decoder").options(),
                    response.cached(cache, embedder, "embedder").options(), env,
                    IntArray(prefill.length()) { prefill.getInt(it) }, List(states.length()) { states.getString(it) },
                    parent = resources, checkActive = checkActive, maxPrefill = cpu.batch)
                check(lm.cacheLength == 4096) { "Unexpected LiteRT context layout." }
                gate(500_000_000)
                fun model(suffix: String) = pack.additionalArtifacts.single { it.name.contains(suffix) }.let { File(directory, it.name) }
                val depthFile = model("depth_frame2")
                val depth = DepthDecoder.frame2(Graph(depthFile, "depth_frame", media.cached(cache, depthFile, "depth"), env, resources))
                val frameFile = model("frame_embed")
                val frame = FrameEmbed(frameFile, media.cached(cache, frameFile, "frame"), env, resources)
                val windowBytes = context.assets.open("litert-codec-window.bin").use { it.readBytes() }
                check(windowBytes.size == 5120)
                val window = ByteBuffer.wrap(windowBytes).order(ByteOrder.LITTLE_ENDIAN)
                val codecFile = model("codec_decoder")
                val codec = CodecDecoder(codecFile, media.cached(cache, codecFile, "codec"), env, resources, FloatArray(1280) { window.float })
                gate(500_000_000)
                val audioFile = files.getValue("audio")
                val audio = AudioEncoder(audioFile, media.cached(cache, audioFile, "audio"), env, resources)
                gate(500_000_000)
                val visionFile = files.getValue("vision_encoder"); val adapterFile = files.getValue("vision_adapter")
                val vision = VisionEncoder(visionFile, adapterFile, media.cached(cache, visionFile, "vision"),
                    media.cached(cache, adapterFile, "adapter"), env, resources)
                checkActive()
                return LiteRtSession(tokenizer, lm, depth, frame, codec, audio, vision, resources)
            } catch (error: Throwable) { resources.close(); throw error }
        }

        private fun hash(file: File, checkActive: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return hex(digest.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
