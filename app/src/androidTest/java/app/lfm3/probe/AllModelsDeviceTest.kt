package app.lfm3.probe

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.*
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.models.*
import app.lfm3.probe.presentation.parseThinking
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Explicit developer opt-in only. Synthetic fixtures, no saved conversation or user media is submitted. */
@RunWith(AndroidJUnit4::class)
class AllModelsDeviceTest {
    @Test fun pinnedPackMatrix() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("All-pack inference requires explicit opt-in.", args.getString("runAllModelTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as TaviApplication
        val pack = ModelCatalog.get(checkNotNull(args.getString("packId")))
        val contextTokens = args.getString("contextTokens")?.toIntOrNull() ?: 4096
        require(contextTokens in GenerationLimits.contexts)
        val root = File(context.noBackupFilesDir, "models")
        val installer = PackInstaller(root, pack = pack)
        val preexisting = installer.installed.exists()
        val reportDir = File(context.noBackupFilesDir, "model-qa").apply { mkdirs() }
        val reportFile = File(reportDir, "${pack.id}.json")
        val cases = JSONArray()
        val report = JSONObject().put("pack", pack.id).put("revision", pack.revision).put("context", contextTokens)
            .put("environment", "ARM64 Android emulator; not phone performance/quality qualification")
            .put("cases", cases).put("stage", "starting")
        fun checkpoint() { reportFile.writeText(report.toString(2)) }
        checkpoint()
        val ownedMedia = mutableListOf<File>()
        val media = MediaStore(context)
        val engine = RemoteLlamaEngine(context)
        var loaded = false
        var turn = 0L
        val observedPss = AtomicInteger()
        val memory = context.getSystemService(ActivityManager::class.java)
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                val ids = listOf(Process.myPid(), engine.remotePid).filter { it > 0 }.toIntArray()
                runCatching { memory.getProcessMemoryInfo(ids).sumOf { it.totalPss } }.getOrNull()?.let { observedPss.accumulateAndGet(it, ::maxOf) }
                delay(500)
            }
        }
        suspend fun testCase(name: String, block: suspend (JSONObject) -> Unit) {
            val result = JSONObject().put("name", name)
            val start = System.nanoTime()
            try { withTimeout(240_000) { block(result) }; result.put("status", "PASS") }
            catch (error: Exception) { result.put("status", "FAIL").put("error", error.javaClass.simpleName)
                .put("reason", error.message?.take(240)?.takeIf { '/' !in it } ?: "See fixture-only instrumentation log") }
            catch (error: AssertionError) { result.put("status", "FAIL").put("reason", error.message?.take(240)) }
            result.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000)
            cases.put(result); checkpoint()
        }
        data class Reply(val body: String, val usage: InferenceEvent.Usage?, val limited: Boolean)
        suspend fun ask(result: JSONObject, parts: List<RequestPart>, history: List<ChatMessage> = emptyList(), speak: Boolean = false, system: String = ""): Reply {
            check(loaded && engine.loaded) { "Model load failed or runtime disconnected." }
            val text = StringBuilder(); var usage: InferenceEvent.Usage? = null; var completed = false; var limited = false
            val start = System.nanoTime(); var first: Long? = null
            engine.generate(InferenceRequest(++turn, parts, history, engine.prepare(), 128, speak, system)) { event -> when (event) {
                is InferenceEvent.TextDelta -> { if (first == null) first = (System.nanoTime() - start) / 1_000_000; text.append(event.value) }
                is InferenceEvent.Usage -> {
                    usage = event
                    // Retain measurements even when a slow codec reaches the test deadline.
                    result.put("audio_samples", event.audioSamples).put("audio_rate", event.audioRate)
                        .put("audio_peak", event.audioPeak.toDouble()).put("first_decoded_audio_ms", event.firstAudioMs ?: JSONObject.NULL)
                }
                is InferenceEvent.Completed -> { completed = true; limited = event.outputLimitReached }
                is InferenceEvent.Failed -> error(event.reason)
            } }
            check(completed) { "No terminal generation event." }
            val body = parseThinking(text.toString(), false).filterNot { it.thinking }.joinToString("") { it.text }
            result.put("text", body.take(4000)).put("limited", limited).put("first_text_ms", first ?: JSONObject.NULL)
            usage?.let { result.put("tokens", it.outputTokens).put("decode_nanos", it.decodeNanos)
                .put("audio_samples", it.audioSamples).put("audio_rate", it.audioRate).put("audio_peak", it.audioPeak.toDouble())
                .put("first_decoded_audio_ms", it.firstAudioMs ?: JSONObject.NULL) }
            return Reply(body, usage, limited)
        }
        fun photo(color: Int): RequestPart.Image {
            val file = media.newFile("jpg"); ownedMedia += file
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } } finally { bitmap.recycle() }
            return RequestPart.Image(file.name)
        }
        fun audio(name: String): RequestPart.Audio {
            val fixture = File(context.noBackupFilesDir, "model-qa-fixtures/$name.wav")
            check(fixture.isFile && fixture.length() in 100..1_000_000) { "Missing synthetic audio fixture." }
            val metadata = JSONObject(File(fixture.parentFile, "manifest.json").readText()).getJSONObject(name)
            val digest = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == metadata.getString("sha256")) { "Synthetic fixture checksum mismatch." }
            val target = media.newFile("wav"); ownedMedia += target; fixture.copyTo(target, overwrite = true)
            return RequestPart.Audio(target.name, metadata.getLong("duration_ms"))
        }
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(30_000) { while (app.runtime.state.value.nativeInfo == null || app.runtime.state.value.busy) delay(100) }
            check(!app.runtime.state.value.loaded && !app.downloads.state.value.active) { "Unload the UI model and pause transfers before QA." }
            if (!installer.verifyInstalled()) {
                check(args.getString("waitForUpload") == "true" && !preexisting) { "Install the verified pack or opt into the staging upload." }
                val nonce = UUID.randomUUID().toString()
                val done = File(reportDir, "upload-${pack.id}-$nonce.done")
                PackLease.acquire(root, pack, shared = false).use {
                    report.put("stage", "awaiting_upload").put("nonce", nonce); checkpoint()
                    withTimeout(600_000) { while (!done.exists()) delay(100) }
                    done.delete()
                }
                report.put("stage", "installing"); checkpoint()
                installer.install { _, _ -> }
            }
            check(installer.verifyInstalled())
            report.put("stage", "testing"); checkpoint()
            testCase("load_and_capabilities") { result ->
                engine.info(); engine.load(pack, contextTokens); loaded = true
                val expected = pack.capabilities.map { when (it) {
                    ModelCapability.TEXT -> Capability.TEXT; ModelCapability.PHOTO -> Capability.IMAGE
                    ModelCapability.VOICE_INPUT -> Capability.AUDIO_INPUT; ModelCapability.VOICE_OUTPUT -> Capability.AUDIO_OUTPUT
                } }.toSet()
                assertEquals(expected, engine.descriptor.capabilities); result.put("capabilities", JSONArray(expected.map { it.name }))
            }
            testCase("text") { result ->
                // A saved Speak preference can outlive switching to a pack without a speech codec.
                val unsupportedSpeech = ModelCapability.VOICE_OUTPUT !in pack.capabilities
                result.put("unsupported_speech_flag", unsupportedSpeech)
                val reply = ask(result, listOf(RequestPart.Text("What is 2 plus 2? Answer briefly.")), speak = unsupportedSpeech)
                assertTrue("Expected four/4", Regex("(?i)\\b(4|four)\\b").containsMatchIn(reply.body))
                assertEquals(0L, reply.usage?.audioSamples ?: 0L)
            }
            if (ModelCapability.PHOTO in pack.capabilities) {
                for ((name, color) in listOf("red" to Color.RED, "blue" to Color.BLUE)) testCase("photo_$name") { result ->
                    val reply = ask(result, listOf(RequestPart.Text("What is the dominant color of this image? Answer with the color."), photo(color)))
                    assertTrue("Expected $name", reply.body.contains(name, true))
                }
                testCase("photo_followup") { result ->
                    val image = photo(Color.RED)
                    val reply = ask(result, listOf(RequestPart.Text("What color was the image in my previous question?")),
                        listOf(ChatMessage("user", "What color is this image?", listOf(image)), ChatMessage("assistant", "The image is red.")))
                    assertTrue("Expected red from replayed image history", reply.body.contains("red", true))
                }
            }
            if (ModelCapability.VOICE_INPUT in pack.capabilities) {
                testCase("audio_transcription") { result ->
                    val reply = ask(result, listOf(audio("transcribe")), system = "Perform ASR in English.")
                    assertTrue("Expected sky and blue", reply.body.contains("sky", true) && reply.body.contains("blue", true))
                }
                testCase("question_transcription") { result ->
                    val reply = ask(result, listOf(audio("question")), system = "Perform ASR in English.")
                    assertTrue("Question was not recognized accurately", Regex("(?i)\\b(two|2)\\b.*\\bplus\\b.*\\b(two|2)\\b").containsMatchIn(reply.body))
                }
                testCase("spoken_question") { result ->
                    val reply = ask(result, listOf(audio("question")))
                    assertTrue("Expected four/4", Regex("(?i)\\b(4|four)\\b").containsMatchIn(reply.body))
                }
            }
            if (ModelCapability.PHOTO in pack.capabilities && ModelCapability.VOICE_INPUT in pack.capabilities)
                for ((name, color) in listOf("red" to Color.RED, "blue" to Color.BLUE)) testCase("photo_plus_audio_$name") { result ->
                    val reply = ask(result, listOf(photo(color), audio("color")))
                    assertTrue("Expected image-dependent $name", reply.body.contains(name, true))
                }
            if (ModelCapability.VOICE_OUTPUT in pack.capabilities) testCase("generated_speech") { result ->
                val reply = ask(result, listOf(RequestPart.Text("Say hello in one short sentence.")), speak = true)
                val stats = checkNotNull(reply.usage)
                assertTrue("No PCM speech was produced", stats.audioSamples > 0 && stats.audioRate in 8000..48000 && stats.audioPeak > 0.00001f)
                assertTrue("Expected a short greeting, not unrelated or capped output", Regex("(?i)\\b(hello|hi|hey)\\b").containsMatchIn(reply.body) && !reply.limited)
            }
            if (ModelCapability.VOICE_OUTPUT in pack.capabilities) {
                if (ModelCapability.PHOTO in pack.capabilities) testCase("photo_plus_spoken_reply") { result ->
                    val reply = ask(result, listOf(photo(Color.BLUE), audio("color")), speak = true)
                    assertTrue("Expected blue in the combined voice reply", reply.body.contains("blue", true) && !reply.limited)
                    val stats = checkNotNull(reply.usage)
                    assertTrue("No speech in the photo/voice reply", stats.audioSamples > 0 && stats.audioPeak > 0.00001f)
                }
                testCase("spoken_reply") { result ->
                    val reply = ask(result, listOf(audio("question")), speak = true)
                    assertTrue("Expected four/4 in the spoken answer", Regex("(?i)\\b(4|four)\\b").containsMatchIn(reply.body) && !reply.limited)
                    val stats = checkNotNull(reply.usage)
                    assertTrue("No speech in the voice reply", stats.audioSamples > 0 && stats.audioPeak > 0.00001f)
                }
                testCase("speech_cancellation_and_reuse") { result ->
                    val started = CompletableDeferred<Unit>()
                    val request = InferenceRequest(++turn, listOf(RequestPart.Text("Explain how rain forms.")),
                        generationTicket = engine.prepare(), outputLimit = 128, speakResponse = true)
                    val generation = launch { try { engine.generate(request) {
                        if (it is InferenceEvent.Usage && it.audioSamples > 0) started.complete(Unit)
                    } } catch (error: Exception) { started.completeExceptionally(error) } }
                    try { withTimeout(60_000) { started.await() } }
                    finally { engine.cancel(); generation.cancelAndJoin() }
                    val reply = ask(result, listOf(RequestPart.Text("What is 2 plus 2? Answer briefly.")))
                    assertTrue(Regex("(?i)\\b(4|four)\\b").containsMatchIn(reply.body))
                }
            }
            testCase("cancellation_and_reuse") { result ->
                check(loaded && engine.loaded)
                val started = CompletableDeferred<Unit>()
                val request = InferenceRequest(++turn, listOf(RequestPart.Text("Count from one to one hundred.")), generationTicket = engine.prepare(), outputLimit = 128)
                val generation = launch { try { engine.generate(request) { if (it is InferenceEvent.TextDelta) started.complete(Unit) } }
                    catch (error: Exception) { started.completeExceptionally(error) } }
                try { withTimeout(60_000) { started.await() } }
                finally { engine.cancel(); generation.cancelAndJoin() }
                val reply = ask(result, listOf(RequestPart.Text("What is 2 plus 2? Answer briefly.")))
                assertTrue(Regex("(?i)\\b(4|four)\\b").containsMatchIn(reply.body))
            }
        } catch (error: Exception) {
            report.put("fatal", error.javaClass.simpleName).put("reason", error.message?.take(240)?.takeIf { '/' !in it } ?: "Setup failed")
        } finally {
            withContext(NonCancellable) {
                runCatching { engine.unload() }.onFailure { if (engine.remotePid > 0) Process.killProcess(engine.remotePid) }
                sampler.cancelAndJoin(); ownedMedia.forEach { it.delete() }
                if (!preexisting && pack.id != ModelCatalog.text.id && args.getString("cleanupTestPack") == "true")
                    runCatching { installer.remove() }.onFailure { report.put("cleanup", "lease/storage operation failed; files retained") }
                report.put("observed_max_own_pss_kib", observedPss.get()).put("memory_note", "OS-cached snapshots, not true peak RAM")
                    .put("stage", "finished")
                checkpoint()
            }
        }
        assertFalse("Setup failed; inspect fixture-only QA report", report.has("fatal"))
        assertTrue("Some fixture cases failed; inspect QA report", (0 until cases.length()).all { cases.getJSONObject(it).getString("status") == "PASS" })
    }
}
