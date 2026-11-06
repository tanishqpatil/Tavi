package app.lfm3.probe.online

import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.URLEncoder

/** Keyless providers, with no scraping, CAPTCHA bypass, automatic fallback or arbitrary page fetching. */
class WebSearch(private val http: ToolHttp) {
    suspend fun search(config: OnlineConfig, query: String): ToolResult = withTimeout(60_000) {
        require(query.length in 1..600 && query.none { it.isISOControl() })
        when (config.provider) {
            SearchProvider.EXA -> {
                val client = McpClient(endpoint(EXA_URL), http)
                client.connect()
                val schema = client.tools().singleOrNull { it.name == "web_search_exa" }
                    ?: error("Exa no longer offers the configured search tool.")
                val arguments = arguments(SearchProvider.EXA, query)
                ToolPolicy.arguments(schema.schema, arguments)
                check(schema.supported) { "Exa changed its search safety annotations or schema; review is needed." }
                val result = client.call("web_search_exa", arguments)
                val raw = McpClient.text(result, 24_000)
                // Exa's documented tool returns labeled text. URLs come from its response, never from generated prose.
                val matches = Regex("(?m)^Title: (.+)\\r?\\nURL: (https://[^\\s]+)").findAll(raw).take(3).toList()
                val rows = matches.mapIndexedNotNull { index, match -> runCatching {
                    val source = SourceLink(match.groupValues[1].take(200), publicLink(match.groupValues[2]))
                    val end = matches.getOrNull(index + 1)?.range?.first ?: raw.length
                    source to raw.substring(match.range.first, end).take(800)
                }.getOrNull() }.distinctBy { it.first.url }
                val sources = rows.map { it.first }
                check(sources.isNotEmpty()) { "Search returned no usable source links. No sourced answer was generated." }
                val excerpts = rows.mapIndexed { index, row -> "[${index + 1}] ${row.first.url}\n${row.second}" }.joinToString("\n")
                ToolResult(excerpts.take(3000), ToolReceipt("Exa", "web_search_exa", System.currentTimeMillis(), sources))
            }
            SearchProvider.SEARXNG -> {
                val base = endpoint(config.searxUrl)
                val uri = java.net.URI(base.toASCIIString().trimEnd('/') + "/search?q=" + URLEncoder.encode(query, "UTF-8") + "&format=json&language=en&safesearch=1")
                val reply = http.request(uri, null, mapOf("Accept" to "application/json"))
                check(reply.status in 200..299) { httpError(reply.status) }
                check(reply.type.startsWith("application/json")) { "This SearXNG instance has not enabled JSON search. Choose an API-enabled instance." }
                val values = JSONObject(reply.body).getJSONArray("results")
                val rows = (0 until minOf(values.length(), 20)).mapNotNull { i ->
                    val row = values.getJSONObject(i)
                    runCatching { SourceLink(row.getString("title").take(200), publicLink(row.getString("url"))) to row.optString("content").take(700) }.getOrNull()
                }.distinctBy { it.first.url }.take(3)
                check(rows.isNotEmpty()) { "No search results were returned. Try a different query or instance." }
                ToolResult(rows.mapIndexed { i, row -> "[${i + 1}] ${row.first.title}\n${row.first.url}\n${row.second}" }.joinToString("\n"),
                    ToolReceipt("SearXNG · ${base.host}", "search", System.currentTimeMillis(), rows.map { it.first }))
            }
        }
    }
    companion object {
        const val EXA_URL = "https://mcp.exa.ai/mcp"
        /** Shared with the approval preview so the user reviews exactly the provider arguments. */
        fun arguments(provider: SearchProvider, query: String): JSONObject = when (provider) {
            SearchProvider.EXA -> JSONObject().put("query", query).put("objective", "Find reliable sources that answer: $query").put("numResults", 3)
            SearchProvider.SEARXNG -> JSONObject().put("q", query).put("format", "json").put("language", "en").put("safesearch", 1)
        }
    }
}
