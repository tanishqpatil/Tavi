package app.lfm3.probe.online

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class OnlineMode(val label: String) { OFF("Offline"), ASK("Ask first"), AUTO("Auto search") }
enum class SearchProvider(val label: String) { EXA("Exa · keyless MCP"), SEARXNG("SearXNG · your instance") }
data class OnlineConfig(
    val mode: OnlineMode = OnlineMode.OFF,
    val provider: SearchProvider = SearchProvider.EXA,
    val searxUrl: String = "",
    val mcpUrl: String = "",
    val enabledTools: Set<String> = emptySet(),
)
data class RemoteTool(val name: String, val description: String, val schema: JSONObject, val supported: Boolean)
data class SourceLink(val title: String, val url: String)
data class ToolReceipt(val provider: String, val operation: String, val time: Long, val sources: List<SourceLink>) {
    fun json() = JSONObject().apply {
        put("provider", provider); put("operation", operation); put("time", time)
        put("sources", JSONArray().apply { sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } })
    }
    companion object {
        fun decode(value: JSONObject): ToolReceipt {
            val provider = value.getString("provider"); val operation = value.getString("operation")
            require(provider.length <= 120 && operation.length <= 120)
            val sources = value.getJSONArray("sources"); require(sources.length() <= 3)
            return ToolReceipt(provider, operation, value.getLong("time"), (0 until sources.length()).map {
                val source = sources.getJSONObject(it); val title = source.getString("title"); require(title.length <= 200)
                SourceLink(title, publicLink(source.getString("url")))
            })
        }
    }
}
data class ToolResult(val text: String, val receipt: ToolReceipt)
data class ToolApproval(val id: Long, val destination: String, val operation: String, val arguments: String)
data class ToolProgress(val stage: String = "", val approval: ToolApproval? = null, val receipt: ToolReceipt? = null)

sealed interface ToolDecision {
    data object Answer : ToolDecision
    data class Web(val query: String) : ToolDecision
    data class Mcp(val tool: String, val arguments: JSONObject) : ToolDecision
}

/** A deliberately small, auditable schema subset. Unsupported schemas are never offered to the model. */
object ToolPolicy {
    fun supported(schema: JSONObject): Boolean = runCatching {
        require(schema.optString("type") == "object")
        require(schema.keys().asSequence().all { it in setOf("type", "properties", "required", "additionalProperties", "description", "\$schema", "title") })
        require(schema.opt("additionalProperties") == false)
        val properties = schema.getJSONObject("properties"); require(properties.length() <= 6)
        properties.keys().asSequence().forEach { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")))
            val item = properties.getJSONObject(name)
            require(item.keys().asSequence().all { it in setOf("type", "description", "title", "enum", "minLength", "maxLength", "minimum", "maximum", "default") })
            val type = item.getString("type")
            require(type in setOf("string", "number", "integer", "boolean"))
            for (key in listOf("minLength", "maxLength")) if (item.has(key)) {
                val bound = item.get(key)
                require(type == "string" && bound is Number && bound.toDouble().isFinite() && bound.toDouble() % 1.0 == 0.0 && bound.toDouble() in 0.0..Int.MAX_VALUE.toDouble())
            }
            require(item.optInt("minLength", 0) <= item.optInt("maxLength", Int.MAX_VALUE))
            for (key in listOf("minimum", "maximum")) if (item.has(key)) {
                val bound = item.get(key)
                require(type in setOf("integer", "number") && bound is Number && bound.toDouble().isFinite())
            }
            require(item.optDouble("minimum", -Double.MAX_VALUE) <= item.optDouble("maximum", Double.MAX_VALUE))
            if (item.has("enum")) {
                val values = item.getJSONArray("enum"); require(values.length() in 1..20)
                for (i in 0 until values.length()) require(when (type) {
                    "string" -> values.get(i) is String
                    "boolean" -> values.get(i) is Boolean
                    else -> values.get(i) is Number && (values.get(i) as Number).toDouble().isFinite()
                })
            }
        }
        (if (schema.has("required")) schema.getJSONArray("required") else null)?.let { required ->
            require(required.length() <= properties.length())
            for (i in 0 until required.length()) require(properties.has(required.getString(i)))
        }
        require(schema.toString().length <= 1800)
        true
    }.getOrDefault(false)

    fun arguments(schema: JSONObject, arguments: JSONObject) {
        require(supported(schema)) { "Unsupported tool schema." }
        require(arguments.toString().length <= 1600) { "Tool arguments exceed the limit." }
        val properties = schema.getJSONObject("properties")
        schema.optJSONArray("required")?.let { for (i in 0 until it.length()) require(arguments.has(it.getString(i))) { "Missing required tool argument." } }
        arguments.keys().asSequence().forEach { name ->
            require(properties.has(name)) { "Unknown tool argument." }
            val rule = properties.getJSONObject(name); val value = arguments.get(name)
            when (rule.getString("type")) {
                "string" -> require(value is String && value.length in rule.optInt("minLength", 0).coerceAtLeast(0)..rule.optInt("maxLength", 800).coerceAtMost(800)) { "Invalid string argument." }
                "number", "integer" -> {
                    require(value is Number && value.toDouble().isFinite()) { "Invalid numeric argument." }
                    val number = value.toDouble()
                    require(number >= rule.optDouble("minimum", -Double.MAX_VALUE) && number <= rule.optDouble("maximum", Double.MAX_VALUE))
                    if (rule.getString("type") == "integer") require(number % 1.0 == 0.0)
                }
                "boolean" -> require(value is Boolean)
            }
            rule.optJSONArray("enum")?.let { values -> require((0 until values.length()).any {
                val candidate = values.get(it)
                if (value is Number && candidate is Number) value.toDouble() == candidate.toDouble() else candidate == value
            }) }
        }
    }

    fun parse(text: String, tools: List<RemoteTool>): ToolDecision {
        require(text.length <= 2400 && text.trim().startsWith('{') && text.trim().endsWith('}')) { "The model did not finish a valid tool decision. No request was sent." }
        val value = JSONObject(text)
        fun keys(vararg allowed: String) { require(value.keys().asSequence().toSet() == allowed.toSet()) { "Unexpected routing fields." } }
        return when (value.getString("action")) {
            "answer" -> { keys("action"); ToolDecision.Answer }
            "web" -> {
                keys("action", "query"); val query = value.getString("query").trim()
                require(query.length in 1..600 && query.none { it.isISOControl() }) { "Invalid search query. No request was sent." }
                ToolDecision.Web(query)
            }
            "mcp" -> {
                keys("action", "tool", "arguments"); val name = value.getString("tool")
                val tool = tools.singleOrNull { it.name == name && it.supported } ?: error("The model selected an unavailable tool. No request was sent.")
                val arguments = value.getJSONObject("arguments"); arguments(tool.schema, arguments)
                ToolDecision.Mcp(name, arguments)
            }
            else -> error("Unknown model decision. No request was sent.")
        }
    }
}

/** Configuration never accepts embedded credentials, redirects or model-generated endpoints. */
fun endpoint(value: String): URI {
    require(value.length in 1..2048)
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Enter an HTTPS endpoint without credentials, query parameters or fragments."
    }
    return uri
}
fun publicLink(value: String): String {
    require(value.length <= 2048)
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
    return uri.toASCIIString()
}

/** Do not let remote text manufacture ChatML control tokens when the native template is tokenized. */
internal fun untrustedText(value: String) = value.replace("<|", "< | ").replace("|>", " | >").replace("<__media__>", "[media marker removed]")
