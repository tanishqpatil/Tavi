package app.lfm3.probe.inference

import android.content.Context
import android.os.Bundle
import app.lfm3.probe.history.ConversationRepository
import app.lfm3.probe.models.ModelPack
import app.lfm3.probe.models.ModelCapability
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Binder carries only bounded control/events; prompts travel through app-private request files. */
internal object InferenceWire {
    const val COMMAND = 1
    const val RESPONSE = 2
    const val ACK = 3
    const val CANCEL = 4
    const val MAX_REQUEST = 512 * 1024
    fun directory(context: Context) = File(context.noBackupFilesDir, "inference-requests").apply { mkdirs() }
    fun requestFile(context: Context, name: String): File {
        require(name.matches(Regex("[a-f0-9-]{36}\\.json"))) { "Invalid runtime request reference." }
        return File(directory(context), name)
    }
    fun encode(context: Context, request: InferenceRequest): File {
        val messages = request.history + ChatMessage("user", request.parts.filterIsInstance<RequestPart.Text>().joinToString("\n") { it.value }, request.parts.filterNot { it is RequestPart.Text })
        val json = JSONObject().apply {
            put("turn", request.turnId); put("output", request.outputLimit); put("speak", request.speakResponse)
            put("system", request.systemInstruction); put("routing", request.routingJson)
            put("messages", JSONArray().apply { messages.forEach { message -> put(JSONObject().apply {
                put("role", message.role); put("text", message.text); put("media", ConversationRepository.encodeMedia(message.media))
            }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_REQUEST) { "Conversation exceeds the runtime transfer budget. Start a new chat; saved history is retained." }
        val file = requestFile(context, "${UUID.randomUUID()}.json")
        try { file.outputStream().use { it.write(json); it.fd.sync() }; return file }
        catch (error: Exception) { file.delete(); throw error }
    }
    fun decode(file: File, ticket: Long): InferenceRequest {
        require(file.isFile && file.length() in 1..MAX_REQUEST.toLong()) { "Invalid runtime request size." }
        val json = JSONObject(file.readText())
        val values = json.getJSONArray("messages"); require(values.length() in 1..1001)
        val messages = (0 until values.length()).map { index ->
            val value = values.getJSONObject(index); val role = value.getString("role"); require(role in setOf("user", "assistant"))
            val text = value.getString("text"); require(text.length <= 32_768)
            ChatMessage(role, text, ConversationRepository.decodeMedia(value.getJSONArray("media")))
        }
        val last = messages.last(); require(last.role == "user")
        val output = json.getInt("output"); require(output in 64..2048)
        val system = json.optString("system"); require(system.length <= 12_000)
        return InferenceRequest(json.getLong("turn"), listOf(RequestPart.Text(last.text)) + last.media,
            messages.dropLast(1), ticket, output, json.getBoolean("speak"), system, json.optBoolean("routing"))
    }
    fun descriptor(pack: ModelPack) = EngineDescriptor("llama.cpp v0.8.0 · ${pack.title} · CPU", pack.capabilities.map { when (it) {
        ModelCapability.TEXT -> Capability.TEXT; ModelCapability.PHOTO -> Capability.IMAGE
        ModelCapability.VOICE_INPUT -> Capability.AUDIO_INPUT; ModelCapability.VOICE_OUTPUT -> Capability.AUDIO_OUTPUT
    } }.toSet(), EngineKind.LLAMA_CPP)
    fun data(id: Long, kind: String) = Bundle().apply { putLong("id", id); putString("kind", kind) }
}

class RuntimeDisconnectedException : IllegalStateException("The runtime process stopped. Partial output was saved. Reload the model and retry when ready; nothing was replayed.")
