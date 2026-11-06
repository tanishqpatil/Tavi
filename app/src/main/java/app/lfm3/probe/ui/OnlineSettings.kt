package app.lfm3.probe.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lfm3.probe.online.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OnlineSettings(online: OnlineTools) {
    val config by online.config.collectAsStateWithLifecycle()
    val tools by online.tools.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var searx by remember(config.searxUrl) { mutableStateOf(config.searxUrl) }
    var mcp by remember(config.mcpUrl) { mutableStateOf(config.mcpUrl) }
    var notice by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf<OnlineMode?>(null) }
    var connecting by remember { mutableStateOf(false) }
    fun save(value: OnlineConfig) {
        try { online.configure(value); notice = "Saved" }
        catch (error: Exception) { notice = error.message ?: "Unable to save online settings." }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ONLINE TOOLS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("A little help from the web", style = MaterialTheme.typography.titleLarge)
        Text("Local answers first. The text model decides whether one search or selected MCP tool would help. Routing can be wrong; check sources.", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnlineMode.entries.forEach { mode -> FilterChip(config.mode == mode,
                { if (mode == OnlineMode.OFF) save(config.copy(mode = mode)) else consent = mode }, label = { Text(mode.label) }) }
        }
        Text("Requires LFM3 Text, 2048+ loaded context and text-only history. Photo/voice packs stay local with online tools off. One call per turn; no autonomous task loop.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Search provider", style = MaterialTheme.typography.titleSmall)
        SearchProvider.entries.forEach { provider -> FilterChip(config.provider == provider, { save(config.copy(provider = provider)) }, label = { Text(provider.label) }) }
        Text("Exa needs no key. Its MCP server is open source; its hosted search backend is operated by Exa and may impose limits. SearXNG is fully open source, but requires an instance with JSON search enabled.", style = MaterialTheme.typography.bodySmall)
        if (config.provider == SearchProvider.SEARXNG) {
            OutlinedTextField(searx, { searx = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text("SearXNG base URL") }, placeholder = { Text("https://your-instance.example") }, singleLine = true)
            TextButton(onClick = { save(config.copy(searxUrl = searx.trim())) }) { Text("Save SearXNG instance") }
        }
        HorizontalDivider()
        Text("Connect a remote MCP server", style = MaterialTheme.typography.titleSmall)
        Text("Optional: HTTPS, keyless Streamable HTTP endpoints. Connecting sends app/protocol metadata. Enable up to 3 supported tools; every custom tool call requires approval. Read-only labels are server claims, not a guarantee.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(mcp, { mcp = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text("MCP endpoint") }, placeholder = { Text("https://your-server.example/mcp") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !connecting && mcp.isNotBlank(), onClick = {
                try {
                    online.configure(config.copy(mcpUrl = mcp.trim())); connecting = true; notice = null
                    scope.launch {
                        try { val catalog = online.discover(); notice = "${catalog.count { it.supported }} supported tools found" }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) { notice = if (error is IllegalStateException || error is IllegalArgumentException) error.message else "Connection failed. Check the HTTPS endpoint and connectivity." }
                        finally { connecting = false }
                    }
                } catch (error: Exception) { notice = error.message }
            }) { Text(if (connecting) "Connecting…" else "Connect / refresh") }
            TextButton(enabled = !connecting, onClick = { mcp = ""; save(config.copy(mcpUrl = "", enabledTools = emptySet())) }) { Text("Disconnect") }
        }
        tools.forEach { tool ->
            FilterChip(selected = tool.name in config.enabledTools, enabled = tool.supported && (tool.name in config.enabledTools || config.enabledTools.size < 3),
                onClick = { save(config.copy(enabledTools = if (tool.name in config.enabledTools) config.enabledTools - tool.name else config.enabledTools + tool.name)) },
                label = { Text(tool.name + if (!tool.supported) " · unsupported schema or safety labels" else "") })
        }
        if (config.enabledTools.isNotEmpty() && tools.isEmpty()) Text("Reconnect to inspect previously selected tools before using them after an app restart.", style = MaterialTheme.typography.bodySmall)
        Text("No OAuth, API keys, stdio processes, write tools, resource subscriptions or server-initiated requests in this version.", style = MaterialTheme.typography.bodySmall)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    consent?.let { mode -> AlertDialog(
        onDismissRequest = { consent = null }, title = { Text(if (mode == OnlineMode.AUTO) "Allow automatic web searches?" else "Enable online requests with approval?") },
        text = { Text("Model inference stays on your device. The provider receives the model's search query or tool arguments, which may include details from your question or recent text context. Photos, recordings and the full conversation are not uploaded by this feature.\n\n" +
            if (mode == OnlineMode.AUTO) "Web searches can run without another prompt. Custom MCP tools still require approval each time. Turn Offline on at any time to stop online work." else "You can review the destination and exact arguments before every tool call. Turn Offline on at any time to stop online work.") },
        confirmButton = { TextButton(onClick = { save(config.copy(mode = mode)); consent = null }) { Text("Enable ${mode.label}") } },
        dismissButton = { TextButton(onClick = { consent = null }) { Text("Keep current mode") } },
    ) }
}
