package app.lfm3.probe

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lfm3.probe.inference.*
import app.lfm3.probe.online.*
import app.lfm3.probe.history.*
import app.lfm3.probe.media.MediaStore
import app.lfm3.probe.presentation.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OnlineToolsTest {
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "online-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
    }
    private fun schema() = JSONObject("""{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":600},"objective":{"type":"string","minLength":1},"numResults":{"type":"number","minimum":1,"maximum":3}},"required":["query","objective"],"additionalProperties":false}""")
    private class FixtureHttp(val schema: JSONObject) : ToolHttp {
        val methods = mutableListOf<String>()
        var callArgs: JSONObject? = null
        var status = 200
        var mismatch = false
        override suspend fun request(uri: URI, body: String?, headers: Map<String, String>): HttpReply {
            val request = JSONObject(checkNotNull(body)); val method = request.getString("method"); methods += method
            if (method != "initialize") assertEquals(McpClient.VERSION, headers["MCP-Protocol-Version"])
            if (method != "initialize") assertEquals("fixture-session", headers["Mcp-Session-Id"])
            if (method == "notifications/initialized") return HttpReply(202, "", null, "")
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", McpClient.VERSION).put("capabilities", JSONObject().put("tools", JSONObject()))
                "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "web_search_exa").put("inputSchema", schema)
                    .put("annotations", JSONObject().put("readOnlyHint", true).put("destructiveHint", false))))
                "tools/call" -> {
                    callArgs = request.getJSONObject("params").getJSONObject("arguments")
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Title: Android documentation\nURL: https://developer.android.com/\nHighlights:\nOfficial Android documentation.")))
                }
                else -> error("Unexpected method")
            }
            val response = JSONObject().put("jsonrpc", "2.0").put("id", if (mismatch) 999 else request.getInt("id")).put("result", result)
            return HttpReply(status, "text/event-stream", "fixture-session", "event: message\ndata: $response\n\n")
        }
    }
    private class FixtureEngine(val decision: String) : InferenceEngine {
        override val descriptor = EngineDescriptor("fixture", setOf(Capability.TEXT), EngineKind.LLAMA_CPP)
        val requests = mutableListOf<InferenceRequest>()
        override suspend fun generate(request: InferenceRequest, emit: suspend (InferenceEvent) -> Unit) {
            requests += request
            emit(InferenceEvent.TextDelta(request.turnId, if (request.routingJson) decision else "A sourced answer [1]."))
            emit(InferenceEvent.Completed(request.turnId))
        }
    }
    private fun request() = InferenceRequest(1, listOf(RequestPart.Text("Find official Android documentation.")))

    @Test fun offlineAndLocalDecisionNeverContactAProvider() = runBlocking {
        val http = FixtureHttp(schema()); val online = OnlineTools(context(), http)
        val engine = FixtureEngine("""{"action":"answer"}""")
        online.generate(engine, request(), 2048, {}, {})
        assertEquals(1, engine.requests.size); assertFalse(engine.requests.single().routingJson)
        online.configure(OnlineConfig(mode = OnlineMode.ASK))
        online.generate(engine, request(), 2048, {}, {})
        assertTrue(http.methods.isEmpty()); assertEquals(3, engine.requests.size)
    }

    @Test fun searchWaitsForExactApprovalAndPersistsSourceReceipts() = runBlocking {
        val context = context(); val http = FixtureHttp(schema()); val online = OnlineTools(context, http)
        online.configure(OnlineConfig(mode = OnlineMode.ASK))
        val engine = FixtureEngine("""{"action":"web","query":"Android documentation"}""")
        var receipt: ToolReceipt? = null
        online.generate(engine, request(), 2048, { progress ->
            progress.approval?.let {
                assertTrue(http.methods.isEmpty()); assertTrue(it.arguments.contains("Android documentation"))
                assertTrue(JSONObject(it.arguments).has("objective")); assertEquals(3, JSONObject(it.arguments).getInt("numResults"))
                online.approve(it.id + 1, true) // stale callbacks cannot approve this request
                online.approve(it.id, true)
            }
            progress.receipt?.let { receipt = it }
        }, {})
        assertEquals(1, http.methods.count { it == "tools/call" }); assertEquals(2, engine.requests.size)
        assertTrue(engine.requests.first().routingJson); assertFalse(engine.requests.last().routingJson)
        assertTrue(http.callArgs!!.has("objective"))
        val actual = checkNotNull(receipt); assertEquals("https://developer.android.com/", actual.sources.single().url)
        val repository = ConversationRepository(context)
        val saved = SavedConversation(turns = listOf(ProbeTurn.User(1, "question", false, false), ProbeTurn.Assistant(1, "answer", ReplyStatus.COMPLETE, actual)))
        repository.save(saved)
        assertEquals(actual, (ConversationRepository(context).load(saved.id)!!.turns.last() as ProbeTurn.Assistant).receipt)
        val archive = ConversationArchive(repository, MediaStore(context)); val output = ByteArrayOutputStream()
        archive.export(saved.id, output)
        val restored = archive.restore(ByteArrayInputStream(output.toByteArray()))
        assertEquals(actual, (repository.load(restored)!!.turns.last() as ProbeTurn.Assistant).receipt)
    }

    @Test fun decliningAndCancellingApprovalSendNothing() = runBlocking {
        val http = FixtureHttp(schema()); val online = OnlineTools(context(), http)
        online.configure(OnlineConfig(mode = OnlineMode.ASK))
        val engine = FixtureEngine("""{"action":"web","query":"test"}""")
        try {
            online.generate(engine, request(), 2048, { it.approval?.let { a -> online.approve(a.id, false) } }, {})
            fail("Decline accepted")
        } catch (_: IllegalStateException) { }
        assertTrue(http.methods.isEmpty())
        val waiting = CompletableDeferred<Unit>()
        val task = launch { online.generate(engine, request(), 2048, { if (it.approval != null) waiting.complete(Unit) }, {}) }
        withTimeout(2000) { waiting.await() }; task.cancelAndJoin()
        assertTrue(http.methods.isEmpty())
    }

    @Test fun autoSearchStillRequiresApprovalForCustomMcp() = runBlocking {
        val http = FixtureHttp(schema()); val online = OnlineTools(context(), http)
        online.configure(OnlineConfig(mode = OnlineMode.AUTO, mcpUrl = "https://tools.example/mcp"))
        online.discover()
        online.configure(online.config.value.copy(enabledTools = setOf("web_search_exa")))
        http.methods.clear()
        val engine = FixtureEngine("""{"action":"mcp","tool":"web_search_exa","arguments":{"query":"test","objective":"test"}}""")
        var approved = false
        online.generate(engine, request(), 2048, { it.approval?.let { a ->
            assertTrue(http.methods.isEmpty()); approved = true; online.approve(a.id, true)
        } }, {})
        assertTrue(approved); assertEquals(1, http.methods.count { it == "tools/call" })
    }

    @Test fun validationRejectsUnknownToolsInvalidArgumentsAndOversizedBodies() = runBlocking {
        val schema = schema()
        assertTrue(ToolPolicy.supported(schema))
        fun rejected(block: () -> Unit) { try { block(); fail("Invalid input accepted") } catch (_: Exception) {} }
        rejected { ToolPolicy.parse("""{"action":"web","query":"x","extra":true}""", emptyList()) }
        rejected { ToolPolicy.parse("""{"action":"mcp","tool":"delete","arguments":{}}""", emptyList()) }
        rejected { ToolPolicy.arguments(schema, JSONObject().put("query", "x")) }
        rejected { ToolPolicy.arguments(schema, JSONObject().put("query", "x").put("objective", "x").put("numResults", 100)) }
        rejected { endpoint("http://localhost/mcp") }
        rejected { endpoint("https://secret:token@host/mcp") }
        rejected { endpoint("https://host/mcp?key=secret") }
        val dangerous = JSONObject(schema.toString()).put("\$ref", "https://host/schema")
        assertFalse(ToolPolicy.supported(dangerous))
        val http = FixtureHttp(schema).apply { mismatch = true }
        try { McpClient(endpoint("https://example.org/mcp"), http).connect(); fail("Mismatched id accepted") } catch (_: IllegalStateException) { }
        val large = ToolHttp { _, _, _ -> HttpReply(200, "application/json", null, " ".repeat(BoundedHttp.MAX_BYTES + 1)) }
        try { McpClient(endpoint("https://example.org/mcp"), large).connect(); fail("Oversized body accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun sseParsingRequiresCompleteMatchingEventsAndRejectsServerRequests() {
        val response = "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}\n\n"
        assertNull(McpClient.sseResponse(response.dropLast(1), 7))
        assertNull(McpClient.sseResponse(response, 8))
        assertNull(McpClient.sseResponse(response.replace("\"id\":7", "\"id\":\"7\""), 7))
        assertNotNull(McpClient.sseResponse(": heartbeat\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n$response", 7))
        try { McpClient.sseResponse("data: {\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"sampling/createMessage\"}\n\n", 2); fail("Server request accepted") } catch (_: IllegalStateException) { }
    }

    @Test fun searxUsesEncodedJsonApiAndRejectsHtmlAndAccessFailures() = runBlocking {
        var requested: URI? = null
        var type = "application/json"
        var status = 200
        val http = ToolHttp { uri, body, _ ->
            requested = uri; assertNull(body)
            HttpReply(status, type, null, """{"results":[{"title":"Official","url":"https://developer.android.com/","content":"Android reference"},{"title":"Unsafe","url":"intent://bad","content":"excluded"}]}""")
        }
        val config = OnlineConfig(provider = SearchProvider.SEARXNG, searxUrl = "https://search.example/instance")
        val result = WebSearch(http).search(config, "android & kotlin")
        assertEquals("/instance/search", requested!!.path)
        assertTrue(requested!!.rawQuery.contains("q=android+%26+kotlin")); assertTrue(requested!!.rawQuery.contains("format=json"))
        assertEquals(1, result.receipt.sources.size)
        type = "text/html"
        try { WebSearch(http).search(config, "test"); fail("HTML accepted as JSON") } catch (_: IllegalStateException) { }
        type = "application/json"; status = 403
        try { WebSearch(http).search(config, "test"); fail("Access failure accepted") } catch (_: IllegalStateException) { }
    }

    @Test fun cancellingNetworkStopsPipelineAndCannotEmitAFinalAnswer() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val http = ToolHttp { _, _, _ -> entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val online = OnlineTools(context(), http); online.configure(OnlineConfig(mode = OnlineMode.AUTO))
        val engine = FixtureEngine("""{"action":"web","query":"test"}""")
        val task = launch { online.generate(engine, request(), 2048, {}, { fail("Final answer emitted after cancellation") }) }
        withTimeout(2000) { entered.await() }; online.cancel(); task.join()
        assertTrue(cancelled.isCompleted); assertEquals(1, engine.requests.size)
    }

    @Test fun versionOneHistoryMigrationPreservesMessages() = runBlocking {
        val context = context()
        val file = File(context.noBackupFilesDir, "history/conversations.db").apply { parentFile!!.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL,real INTEGER NOT NULL,engine TEXT NOT NULL,draft TEXT NOT NULL,speak INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages(chat TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,turn INTEGER NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,status TEXT NOT NULL,media TEXT NOT NULL,PRIMARY KEY(chat,turn,role))")
            db.execSQL("INSERT INTO conversations VALUES('old','Retained',1,0,'preview','',0)")
            db.execSQL("INSERT INTO messages VALUES('old',1,'user','old question','COMPLETE','[]')")
            db.execSQL("INSERT INTO messages VALUES('old',1,'assistant','old reply','COMPLETE','[]')")
            db.version = 1
        }
        val repository = ConversationRepository(context)
        val chat = checkNotNull(repository.load("old"))
        assertEquals(2, chat.turns.size); assertEquals("old reply", (chat.turns.last() as ProbeTurn.Assistant).text)
        assertNull((chat.turns.last() as ProbeTurn.Assistant).receipt)
        repository.save(chat)
        assertEquals(chat, repository.load("old"))
    }

    @Test fun liveKeylessExaReturnsSources() = runBlocking {
        assumeTrue("Live Internet test is opt-in.", InstrumentationRegistry.getArguments().getString("runOnlineTest") == "true")
        val result = WebSearch(BoundedHttp()).search(OnlineConfig(), "Official Android developer documentation")
        assertTrue(result.receipt.sources.isNotEmpty()); assertTrue(result.text.isNotBlank())
    }
}
