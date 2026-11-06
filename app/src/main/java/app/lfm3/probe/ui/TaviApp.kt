package app.lfm3.probe.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lfm3.probe.R
import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.presentation.*
import java.util.Locale

private enum class Page(val title: String, val icon: String) {
    CHAT("Chat", "chat"), HISTORY("History", "history"), MODELS("Models", "models"), SETTINGS("Settings", "settings")
}

@Composable
fun TaviApp(
    theme: ThemeChoice, developer: Boolean, reduceMotion: Boolean,
    onTheme: (ThemeChoice) -> Unit, onDeveloper: (Boolean) -> Unit, onReduceMotion: (Boolean) -> Unit,
    model: ProbeViewModel = viewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.CHAT) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                Page.entries.forEach { destination ->
                    NavigationBarItem(selected = destination == page, onClick = { page = destination },
                        icon = { Glyph(destination.icon) }, label = { Text(destination.title) })
                }
            }
        },
    ) { insets ->
        Column(Modifier.padding(insets).consumeWindowInsets(insets).imePadding().fillMaxSize().widthIn(max = 840.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.tavi_logo), null, Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(10.dp))
                Text("Tavi", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (page == Page.CHAT) {
                    IconButton(onClick = { clearConfirmation = true }, enabled = state.turns.isNotEmpty()) { Glyph("plus", description = "New chat") }
                } else Text("ON YOUR DEVICE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Crossfade(page, animationSpec = tween(if (reduceMotion) 0 else 180), label = "page") { target ->
                when (target) {
                    Page.CHAT -> ChatPage(state, model, developer, reduceMotion, showPreview,
                        onModels = { page = Page.MODELS }, onPreview = { showPreview = true })
                    Page.MODELS -> ModelsPage()
                    Page.HISTORY -> HistoryPage(state, onOpen = { page = Page.CHAT }, onClear = { clearConfirmation = true })
                    Page.SETTINGS -> SettingsPage(theme, developer, reduceMotion, onTheme, onDeveloper, onReduceMotion)
                }
            }
        }
    }
    if (clearConfirmation) AlertDialog(
        onDismissRequest = { clearConfirmation = false }, title = { Text("Start fresh?") },
        text = { Text("This clears the current preview conversation from memory and stops any response.") },
        confirmButton = { TextButton(onClick = { model.newChat(); clearConfirmation = false; page = Page.CHAT }) { Text("New chat") } },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("Keep chatting") } },
    )
}

@Composable
private fun ChatPage(state: ProbeUiState, model: ProbeViewModel, developer: Boolean, reduced: Boolean,
                     preview: Boolean, onModels: () -> Unit, onPreview: () -> Unit) {
    val list = rememberLazyListState()
    // Follow a stream only when the user is already reading its last message.
    LaunchedEffect(state.turns.lastOrNull(), state.isGenerating) {
        if (state.turns.isNotEmpty() && (!list.canScrollForward || list.layoutInfo.totalItemsCount == 0))
            list.scrollToItem(state.turns.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Surface(onClick = onModels, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("models", tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(if (preview) "UI preview" else "Choose your first model", style = MaterialTheme.typography.labelLarge)
                    Text(if (preview) "Simulated responses · no model loaded" else "Get ready for local conversations", style = MaterialTheme.typography.bodySmall)
                }
                Glyph("arrow")
            }
        }
        if (state.turns.isEmpty()) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    Spacer(Modifier.height(18.dp))
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Glyph("spark", Modifier.size(32.dp), MaterialTheme.colorScheme.primary) }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("A little more\npossibility.", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("A space to think, see, and talk.\nPowered by models on your device.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { FeatureRow("photo", "Look a little closer", "Ask about a photo or something in front of you.") }
                item { FeatureRow("voice", "Find your voice", "Speak a question. Hear a response.") }
                item {
                    Button(onClick = onModels, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text("Explore models"); Spacer(Modifier.width(8.dp)); Glyph("arrow") }
                    if (!preview) TextButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) { Text("Try the interface preview") }
                    Text("Photo and voice inference are being integrated.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(state.turns, key = { "${it.id}-${it is ProbeTurn.User}" }) { turn ->
                Message(turn, Modifier.then(if (reduced) Modifier else Modifier.animateItem()))
            }
        }
        state.error?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        AnimatedVisibility(developer, enter = fadeIn(tween(if (reduced) 0 else 150)), exit = fadeOut(tween(if (reduced) 0 else 100))) {
            DeveloperPanel(state)
        }
        if (preview) {
            Surface(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(8.dp)) {
                    OutlinedTextField(state.draft, model::updateDraft, Modifier.fillMaxWidth(), placeholder = { Text("Ask Tavi anything…") },
                        enabled = !state.isGenerating, maxLines = 4, shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {}, enabled = false) { Glyph("photo", description = "Photo input unavailable") }
                        IconButton(onClick = {}, enabled = false) { Glyph("voice", description = "Voice input unavailable") }
                        Text("Preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(onClick = if (state.isGenerating) model::cancel else model::submit,
                            enabled = state.isGenerating || state.draft.isNotBlank()) { Glyph(if (state.isGenerating) "stop" else "arrow", description = if (state.isGenerating) "Stop response" else "Send message") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Glyph(icon, tint = MaterialTheme.colorScheme.primary) }
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Message(turn: ProbeTurn, modifier: Modifier = Modifier) {
    val user = turn is ProbeTurn.User
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "YOU" else "TAVI · PREVIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
            SelectionContainer {
                Text(when (turn) {
                    is ProbeTurn.User -> turn.text
                    is ProbeTurn.Assistant -> turn.text.ifBlank { "Preparing response…" }
                }, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun DeveloperPanel(state: ProbeUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val speed = state.metrics.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f tok/s", it) } ?: "— tok/s"
    Surface(Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Glyph("code", Modifier.size(18.dp))
                Text("  Developer", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(speed, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { expanded = !expanded }, Modifier.size(48.dp)) { Glyph(if (expanded) "minus" else "plus", Modifier.size(18.dp), description = if (expanded) "Collapse developer details" else "Expand developer details") }
            }
            if (expanded) {
                Text("Engine  deterministic-preview\nTokens  ${state.metrics.outputTokens ?: "unavailable"}\nFirst text  ${state.metrics.firstTextMs?.let { "$it ms" } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                Text("${if (state.isGenerating) "● Streaming" else "○ Idle"} · tokenizer metrics unavailable in preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.trace.takeLast(5).forEach { Text("› $it", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun ModelsPage() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeading("Your models", "One home for your on-device intelligence.") }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Make room for possibility", style = MaterialTheme.typography.titleMedium)
                    Text("No model loaded in RAM. Download and load controls will appear after runtime and artifact verification.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Eyebrow("MODEL LIBRARY") }
        items(ModelCatalog.packs, key = { it.id }) { pack ->
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(if (pack.id.contains("omni")) "spark" else if (pack.id.contains("vl")) "photo" else "voice", tint = MaterialTheme.colorScheme.primary)
                        Text(pack.title, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(when (pack.id) { "lfm3-omni" -> "Text, vision, and voice together."; "lfm3-vl" -> "A new perspective on your photos."; else -> "Conversations beyond the keyboard." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Awaiting verification", style = MaterialTheme.typography.labelMedium)
                    Text("Not installed · Runtime not connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Models have separate download sizes and licenses. Tavi will show both before installation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun HistoryPage(state: ProbeUiState, onOpen: () -> Unit, onClear: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeading("Your conversations", "Pick up a thought where you left it.") }
        item { Text("Preview history stays in this session. Persistent chat storage is being integrated.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.turns.isEmpty()) item { FeatureRow("history", "A fresh start", "Your current conversation will appear here.") }
        else item {
            Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("CURRENT SESSION")
                    Text(state.turns.filterIsInstance<ProbeTurn.User>().firstOrNull()?.text ?: "Conversation", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text("${state.turns.filterIsInstance<ProbeTurn.User>().size} turns · Interface preview", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onClear) { Text("Clear current conversation") }
        }
    }
}

@Composable
private fun SettingsPage(theme: ThemeChoice, developer: Boolean, reduced: Boolean,
                         onTheme: (ThemeChoice) -> Unit, onDeveloper: (Boolean) -> Unit, onReduced: (Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeading("Make it yours", "A calmer space, set up your way.") }
        item {
            Eyebrow("APPEARANCE")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice.entries.forEach { choice -> FilterChip(selected = theme == choice, onClick = { onTheme(choice) }, label = { Text(choice.label) }) }
            }
            SettingSwitch("Reduce motion", "Keep screen changes immediate.", reduced, onReduced)
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp)); Eyebrow("UNDER THE HOOD")
            SettingSwitch("Developer view", "Show chat events, token speed, and response timing.", developer, onDeveloper)
            Text("Verbose details stay on your device. Preview responses do not provide tokenizer counts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp)); Eyebrow("DESIGNED TO STAY LOCAL")
            Spacer(Modifier.height(14.dp))
            FeatureRow("models", "Your device. Your space.", "No cloud inference or account. Diagnostics exclude your conversation content.")
        }
        item {
            Text("Tavi", style = MaterialTheme.typography.titleLarge)
            Text("0.1.0 · Development preview\nBuilt for local text, vision, and voice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, change, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun PageHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Eyebrow(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Tiny vector strokes avoid a large icon/font dependency and bitmap allocations. Parent controls supply labels. */
@Composable
private fun Glyph(kind: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, description: String? = null) {
    Canvas(modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val path = Path()
            fun line(x: Float, y: Float, a: Float, b: Float) { path.moveTo(x,y); path.lineTo(a,b) }
            when (kind) {
                "arrow" -> { line(5f,12f,19f,12f); line(13f,6f,19f,12f); line(19f,12f,13f,18f) }
                "plus" -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
                "minus" -> line(5f,12f,19f,12f)
                "stop" -> { path.moveTo(7f,7f); path.lineTo(17f,7f); path.lineTo(17f,17f); path.lineTo(7f,17f); path.close() }
                "photo" -> { path.moveTo(4f,4f); path.lineTo(20f,4f); path.lineTo(20f,20f); path.lineTo(4f,20f); path.close(); line(4f,17f,10f,11f); line(10f,11f,15f,16f); line(15f,16f,18f,13f); line(18f,13f,20f,15f); drawCircle(tint, 1.5f, androidx.compose.ui.geometry.Offset(15f,8f)) }
                "voice" -> { line(12f,4f,12f,14f); line(8f,8f,8f,15f); line(16f,8f,16f,15f); line(5f,11f,5f,13f); line(19f,11f,19f,13f); line(12f,18f,12f,20f) }
                "chat" -> { path.moveTo(4f,4f); path.lineTo(20f,4f); path.lineTo(20f,17f); path.lineTo(10f,17f); path.lineTo(5f,21f); path.lineTo(5f,17f); path.lineTo(4f,17f); path.close(); line(8f,9f,16f,9f); line(8f,13f,13f,13f) }
                "history" -> { drawCircle(tint, 8f, androidx.compose.ui.geometry.Offset(12f,12f), style = Stroke(1.7f)); line(12f,7f,12f,12f); line(12f,12f,16f,14f) }
                "settings" -> { line(4f,7f,20f,7f); line(4f,17f,20f,17f); drawCircle(tint, 3f, androidx.compose.ui.geometry.Offset(9f,7f), style = Stroke(1.7f)); drawCircle(tint, 3f, androidx.compose.ui.geometry.Offset(15f,17f), style = Stroke(1.7f)) }
                "code" -> { line(8f,6f,3f,12f); line(3f,12f,8f,18f); line(16f,6f,21f,12f); line(21f,12f,16f,18f) }
                "spark" -> { path.moveTo(12f,2f); path.quadraticTo(13f,10f,22f,12f); path.quadraticTo(13f,14f,12f,22f); path.quadraticTo(10f,14f,2f,12f); path.quadraticTo(10f,10f,12f,2f); path.close() }
                else -> { path.moveTo(12f,3f); path.lineTo(21f,8f); path.lineTo(12f,13f); path.lineTo(3f,8f); path.close(); line(3f,12f,12f,17f); line(12f,17f,21f,12f); line(3f,16f,12f,21f); line(12f,21f,21f,16f) }
            }
            drawPath(path, tint, style = Stroke(1.7f, cap = StrokeCap.Round))
        }
    }
}
