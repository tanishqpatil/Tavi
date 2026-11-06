package app.lfm3.probe.online

import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Request-scoped Streamable HTTP, pinned to the initialize-era MCP protocols below.
 * No stdio, OAuth, sampling, elicitation, server instructions or background event stream.
 */
class McpClient(private val uri: URI, private val http: ToolHttp) {
    private var session: String? = null
    private var protocol: String? = null
    private var nextId = 1

    suspend fun connect() {
        val result = rpc("initialize", JSONObject().apply {
            put("protocolVersion", VERSION); put("capabilities", JSONObject())
            put("clientInfo", JSONObject().put("name", "Tavi").put("version", "0.1.0"))
        })
        val negotiated = result.getString("protocolVersion")
        check(negotiated in VERSIONS) { "This server negotiated an unsupported MCP protocol." }
        check(result.optJSONObject("capabilities")?.has("tools") == true) { "This MCP server does not offer tools." }
        protocol = negotiated
        rpc("notifications/initialized", JSONObject(), notification = true)
        // initialize.instructions is remote content and is deliberately ignored.
    }

    suspend fun tools(): List<RemoteTool> {
        check(protocol != null)
        val tools = mutableListOf<RemoteTool>()
        var cursor: String? = null
        val cursors = mutableSetOf<String>()
        repeat(3) {
            val result = rpc("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
            val values = result.getJSONArray("tools"); require(values.length() <= 50 && tools.size + values.length() <= 50) { "MCP catalog exceeds 50 tools." }
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index); val name = value.getString("name")
                require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}")) && tools.none { it.name == name }) { "Invalid or duplicate MCP tool name." }
                val schema = value.getJSONObject("inputSchema")
                val annotations = value.optJSONObject("annotations")
                val supported = annotations?.opt("readOnlyHint") == true &&
                    annotations.opt("destructiveHint") == false && ToolPolicy.supported(schema)
                tools += RemoteTool(name, value.optString("description").take(300), schema, supported)
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (cursor == null) return tools
            require(cursor.length <= 2048 && cursors.add(cursor)) { "Invalid MCP pagination." }
        }
        error("MCP catalog exceeds its pagination budget.")
    }

    suspend fun call(name: String, arguments: JSONObject): JSONObject {
        check(protocol != null)
        val result = rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        check(!result.optBoolean("isError")) { "The remote tool reported a failure. No answer was generated from its output." }
        return result
    }

    private suspend fun rpc(method: String, params: JSONObject, notification: Boolean = false): JSONObject = withTimeout(30_000) {
        val id = nextId++
        val request = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (!notification) request.put("id", id)
        val headers = mutableMapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream")
        session?.let { headers["Mcp-Session-Id"] = it }
        protocol?.let { headers["MCP-Protocol-Version"] = it }
        val reply = http.request(uri, request.toString(), headers)
        check(reply.status in 200..299) { httpError(reply.status) }
        if (method == "initialize") reply.session?.let {
            require(it.length in 1..256 && it.all { char -> char.code in 33..126 })
            session = it
        }
        if (notification) {
            check(reply.status == 202 || reply.status == 204 || reply.body.isBlank()) { "Invalid MCP notification response." }
            return@withTimeout JSONObject()
        }
        require(reply.body.toByteArray(Charsets.UTF_8).size <= BoundedHttp.MAX_BYTES)
        val response = when {
            reply.type.startsWith("application/json") -> JSONObject(reply.body)
            reply.type.startsWith("text/event-stream") -> sseResponse(reply.body, id) ?: error("MCP stream ended without the requested response.")
            else -> error("This endpoint did not return MCP JSON or SSE.")
        }
        require(response.optString("jsonrpc") == "2.0" && sameId(response.opt("id"), id)) { "Mismatched MCP response." }
        check(!response.has("error")) { "The MCP server rejected the request. Check endpoint compatibility and tool configuration." }
        response.getJSONObject("result")
    }

    companion object {
        const val VERSION = "2025-11-25"
        private val VERSIONS = setOf(VERSION, "2025-06-18", "2025-03-26")
        /** Only complete SSE events are parsed. Notifications cannot complete someone else's request. */
        fun sseResponse(body: String, id: Any): JSONObject? {
            val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
            val blocks = normalized.split("\n\n")
            for (block in blocks.dropLast(1)) {
                val data = block.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").removePrefix(" ") }.joinToString("\n")
                if (data.isEmpty()) continue
                val value = JSONObject(data)
                check(!value.has("method") || !value.has("id")) { "Server-initiated MCP requests are unsupported." }
                if (sameId(value.opt("id"), id)) return value
            }
            return null
        }
        private fun sameId(actual: Any?, expected: Any): Boolean = when {
            actual is Number && expected is Number -> actual.toDouble() == expected.toDouble()
            actual is String && expected is String -> actual == expected
            else -> false
        }
        fun text(result: JSONObject, limit: Int = 3000): String {
            val blocks = result.optJSONArray("content") ?: JSONArray()
            require(blocks.length() <= 100)
            val text = StringBuilder()
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                if (block.optString("type") == "text" && text.length < limit) text.append(block.optString("text").take(limit - text.length)).append('\n')
            }
            check(text.isNotBlank()) { "The tool returned no supported text content." }
            return text.toString().take(limit)
        }
    }
}

internal fun httpError(status: Int) = when (status) {
    401, 403 -> "Online access was refused. This client supports keyless endpoints; check the instance's access policy."
    404 -> "MCP endpoint or session is unavailable. Reconnect before trying again."
    429 -> "The provider's free rate limit was reached. Wait before trying again."
    in 300..399 -> "Endpoint redirected. Enter its final HTTPS URL in Settings; redirects are not followed."
    else -> "Online provider returned HTTP $status. No automatic retry was made."
}
