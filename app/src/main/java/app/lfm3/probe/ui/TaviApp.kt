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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import app.lfm3.probe.models.ModelCapability
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lfm3.probe.R
import app.lfm3.probe.TaviApplication
import app.lfm3.probe.models.ModelCatalog
import app.lfm3.probe.models.RuntimeState
import app.lfm3.probe.models.RuntimePhase
import app.lfm3.probe.models.TextPack
import app.lfm3.probe.inference.GenerationLimits
import app.lfm3.probe.presentation.*
import java.util.Locale

private enum class Page(val title: String, val icon: String) {
    CHAT("Chat", "chat"), HISTORY("History", "history"), MODELS("Models", "models"), SETTINGS("Settings", "settings")
}

@Composable
fun TaviApp(
    theme: ThemeChoice, developer: Boolean, reduceMotion: Boolean, limits: GenerationLimits,
    onTheme: (ThemeChoice) -> Unit, onDeveloper: (Boolean) -> Unit, onReduceMotion: (Boolean) -> Unit,
    onLimits: (GenerationLimits) -> Unit,
) {
    val application = LocalContext.current.applicationContext as TaviApplication
    val runtime = application.runtime
    val factory = remember(runtime) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProbeViewModel(runtime.engine, application.media, application.conversations, application.contentResolver) as T
    } }
    val model: ProbeViewModel = viewModel(factory = factory)
    SideEffect { model.outputLimit = limits.output }
    val state by model.state.collectAsStateWithLifecycle()
    val runtimeState by runtime.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestState by rememberUpdatedState(state)
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (latestState.isGenerating) model.cancel()
                model.stopRecording(true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var page by rememberSaveable { mutableStateOf(Page.CHAT) }
    LaunchedEffect(page) { if (page == Page.HISTORY) model.refreshHistory() }
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
                    IconButton(onClick = { clearConfirmation = true }, enabled = state.turns.isNotEmpty() && !state.isHistoryBusy) { Glyph("plus", description = "New chat") }
                } else Text("ON YOUR DEVICE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Crossfade(page, animationSpec = tween(if (reduceMotion) 0 else 180), label = "page") { target ->
                when (target) {
                    Page.CHAT -> ChatPage(state, model, developer, reduceMotion, showPreview, runtimeState,
                        onModels = { page = Page.MODELS }, onPreview = { runtime.usePreview(); model.newChat(); showPreview = true })
                    Page.MODELS -> ModelsPage(runtimeState, state.isGenerating || state.isRecording || state.isImporting, runtime::select, runtime::download, runtime::pauseDownload, runtime::load, runtime::unload, runtime::remove, runtime::unmeteredDownloads)
                    Page.HISTORY -> ConversationHistoryPage(state, model, onOpen = { real -> if (!real && !runtimeState.loaded) runtime.usePreview(); page = Page.CHAT })
                    Page.SETTINGS -> SettingsPage(theme, developer, reduceMotion, onTheme, onDeveloper, onReduceMotion, limits, onLimits)
                }
            }
        }
    }
    if (clearConfirmation) AlertDialog(
        onDismissRequest = { clearConfirmation = false }, title = { Text("Start fresh?") },
        text = { Text("Your conversation stays saved in History. Any running response will stop.") },
        confirmButton = { TextButton(onClick = { model.newChat(); clearConfirmation = false; page = Page.CHAT }) { Text("New chat") } },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("Keep chatting") } },
    )
}

@Composable
private fun ChatPage(state: ProbeUiState, model: ProbeViewModel, developer: Boolean, reduced: Boolean,
                     preview: Boolean, runtime: RuntimeState, onModels: () -> Unit, onPreview: () -> Unit) {
    val viewingPreview = preview || (!state.realInference && state.turns.isNotEmpty())
    val canChat = runtime.loaded || viewingPreview
    val context = LocalContext.current
    var photoMenu by remember { mutableStateOf(false) }
    var captureName by rememberSaveable { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(model::importPhoto) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        captureName?.let { name ->
            val file = File(context.cacheDir, "captures/$name")
            if (success) model.importPhoto(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
            else file.delete()
        }
        captureName = null
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.startRecording() else model.reportError("Microphone permission was denied. You can still type.")
    }
    val capabilities = runtime.loadedId?.let { ModelCatalog.get(it).capabilities } ?: emptySet()
    val list = rememberLazyListState()
    val endIndex = state.turns.lastIndex + if (state.totalTurns > state.turns.size / 2) 1 else 0
    val lastQuestion = state.turns.lastOrNull { it is ProbeTurn.User }?.id
    // Opening a saved chat or sending a question is an explicit request to see its newest turn.
    // Loading older pages keeps the same last-question ID and preserves the reader's position.
    LaunchedEffect(state.conversationId, lastQuestion) {
        if (state.turns.isNotEmpty()) list.scrollToItem(endIndex)
    }
    // Follow a stream only when the user is already reading its last message.
    LaunchedEffect(state.turns.lastOrNull(), state.isGenerating) {
        if (state.turns.isNotEmpty() && (!list.canScrollForward || list.layoutInfo.totalItemsCount == 0))
            list.scrollToItem(endIndex)
    }
    Column(Modifier.fillMaxSize()) {
        if (state.isHistoryBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.turns.isNotEmpty()) Text(state.conversationTitle, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(onClick = onModels, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("models", tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(if (runtime.loaded) ModelCatalog.get(runtime.loadedId!!).title else if (viewingPreview) "UI preview" else "Choose your first model", style = MaterialTheme.typography.labelLarge)
                    Text(if (runtime.loaded) runtime.message else if (viewingPreview) "Simulated responses · no model loaded" else "Get ready for local conversations", style = MaterialTheme.typography.bodySmall)
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
                    if (!canChat) TextButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) { Text("Try the interface preview") }
                    Text(if (runtime.loadedId != null && ModelCatalog.get(runtime.loadedId).experimental) "Experimental media support · phone testing required" else "Choose a vision or audio pack to use media.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.totalTurns > state.turns.size / 2) item {
                TextButton(onClick = model::olderMessages, enabled = !state.isHistoryBusy && !state.isGenerating) { Text("Load older messages (${state.totalTurns} turns saved)") }
            }
            items(state.turns, key = { "${state.conversationId}-${it.id}-${it is ProbeTurn.User}" }) { turn ->
                Message(turn, state.realInference, Modifier.then(if (reduced) Modifier else Modifier.animateItem()), state.isGenerating && turn.id == state.turns.lastOrNull()?.id)
            }
        }
        state.error?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.turns.lastOrNull().let { it is ProbeTurn.Assistant && it.status in setOf(ReplyStatus.INTERRUPTED, ReplyStatus.FAILED) }) {
            TextButton(onClick = model::reviewLastQuestion, enabled = runtime.loaded && !state.isGenerating && !state.isHistoryBusy,
                modifier = Modifier.padding(horizontal = 16.dp)) { Text(if (runtime.loaded) "Review last question to retry" else "Reload the model in Models to retry") }
        }
        AnimatedVisibility(developer, enter = fadeIn(tween(if (reduced) 0 else 150)), exit = fadeOut(tween(if (reduced) 0 else 100))) {
            DeveloperPanel(state)
        }
        if (canChat) {
            Surface(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.photoReference != null) InputChip(selected = true, onClick = model::clearPhoto, label = { Text("Photo · remove") }, leadingIcon = { PhotoThumbnail(state.photoReference) })
                        if (state.audioReference != null) InputChip(selected = true, onClick = model::clearAudio, label = { Text("Voice ${state.audioDurationMs / 1000}s · remove") })
                    }
                    if (state.isRecording) Text("Recording · tap microphone to finish · 30s maximum", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (state.isImporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedTextField(state.draft, model::updateDraft, Modifier.fillMaxWidth(), placeholder = { Text("Ask Tavi anything…") },
                        enabled = !state.isGenerating && !state.isHistoryBusy, maxLines = 4, shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { photoMenu = true }, enabled = ModelCapability.PHOTO in capabilities && !state.isGenerating && !state.isRecording && !state.isImporting) { Glyph("photo", description = "Attach or capture photo") }
                            DropdownMenu(photoMenu, { photoMenu = false }) {
                                DropdownMenuItem(text = { Text("Choose photo") }, onClick = { photoMenu = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                                DropdownMenuItem(text = { Text("Take photo") }, onClick = {
                                    photoMenu = false
                                    val name = "${UUID.randomUUID()}.jpg"
                                    val file = File(context.cacheDir, "captures/$name").apply { parentFile?.mkdirs() }
                                    captureName = name
                                    runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                                        .onFailure { captureName = null; file.delete(); model.reportError("No compatible camera app is available. Choose a photo instead.") }
                                })
                            }
                        }
                        IconButton(onClick = {
                            if (state.isRecording) model.stopRecording()
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.startRecording()
                            else microphone.launch(Manifest.permission.RECORD_AUDIO)
                        }, enabled = ModelCapability.VOICE_INPUT in capabilities && !state.isGenerating && !state.isImporting) { Glyph(if (state.isRecording) "stop" else "voice", description = if (state.isRecording) "Finish voice recording" else "Record voice") }
                        if (ModelCapability.VOICE_OUTPUT in capabilities) FilterChip(state.speakReplies, model::toggleSpeech, label = { Text("Speak") })
                        Text(if (runtime.loaded) "Local · Text" else "Preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(onClick = if (state.isGenerating) model::cancel else model::submit,
                            enabled = !state.isHistoryBusy && runtime.phase != RuntimePhase.LOADING && !state.isRecording && !state.isImporting && (state.isGenerating || state.draft.isNotBlank() || state.photoAttached || state.audioAttached)) { Glyph(if (state.isGenerating) "stop" else "arrow", description = if (state.isGenerating) "Stop response" else "Send message") }
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
private fun Message(turn: ProbeTurn, real: Boolean, modifier: Modifier = Modifier, streaming: Boolean = false) {
    val user = turn is ProbeTurn.User
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "YOU" else if (real) "TAVI · LFM3" else "TAVI · PREVIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (turn is ProbeTurn.User && turn.media.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                turn.media.forEach { part -> when (part) {
                    is app.lfm3.probe.inference.RequestPart.Image -> PhotoThumbnail(part.privateReference)
                    is app.lfm3.probe.inference.RequestPart.Audio -> Text("Voice · ${part.durationMs / 1000}s", style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                } }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
            SelectionContainer {
                if (turn is ProbeTurn.Assistant) {
                    val parts = remember(turn.text, streaming) { parseThinking(turn.text, streaming) }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        parts.forEachIndexed { index, part ->
                            if (part.thinking) {
                                var expanded by rememberSaveable(turn.id, index) { mutableStateOf(false) }
                                TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "−" else "+"} ${if (part.complete) "Thought process" else "Thinking…"}", fontWeight = FontWeight.Light) }
                                if (expanded) Text(part.text, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else Text(part.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (turn.status == ReplyStatus.INTERRUPTED || turn.status == ReplyStatus.FAILED) Text(
                            if (turn.status == ReplyStatus.INTERRUPTED) "Interrupted · partial reply saved" else "Response failed · excluded from follow-up context",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        if (turn.text.isBlank() && turn.status == ReplyStatus.STREAMING) Text("Preparing response…")
                    }
                } else Text((turn as ProbeTurn.User).text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(reference: String) {
    val store = (LocalContext.current.applicationContext as TaviApplication).media
    val bitmap by produceState<android.graphics.Bitmap?>(null, reference) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(store.resolve(reference).path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), "Attached photo", Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) }
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
                Text("Engine  ${state.engineName}\nTokens  ${state.metrics.outputTokens ?: "unavailable"}\nFirst text  ${state.metrics.firstTextMs?.let { "$it ms" } ?: "—"}", style = MaterialTheme.typography.labelSmall)
                Text("${if (state.isGenerating) "● Streaming" else "○ Idle"} · ${if (state.realInference) "exact runtime token counts" else "tokenizer metrics unavailable in preview"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.trace.takeLast(5).forEach { Text("› $it", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun ModelsPage(runtime: RuntimeState, generating: Boolean, select: (String) -> Unit, download: () -> Unit,
                       pause: () -> Unit, load: () -> Unit, unload: () -> Unit, remove: () -> Unit, unmetered: (Boolean) -> Unit) {
    var consent by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val selected = runtime.selected
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { download() }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Download ${selected.title}?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${selected.bytes / 1_000_000} MB · Q4_K_M weights${if (selected.projector != null) " + matching Q8_0 component" else ""}\nLFM Open License v1.0\n\nDownloads continue in the background with progress and Pause controls. All components must verify before loading.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(runtime.unmeteredDownloads, unmetered)
                Text("Unmetered networks only", style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (runtime.unmeteredDownloads) "Waits for an unmetered connection; this can include Wi-Fi or Ethernet." else "Metered/mobile data is allowed. Model packs are large.", style = MaterialTheme.typography.bodySmall)
            if (selected.experimental) Text("Experimental: camera, vision and voice require phone testing. No performance guarantee.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { uri.openUri(selected.licensePage) }) { Text("Read model license") }
        } },
        confirmButton = { TextButton(onClick = {
            consent = false
            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else download()
        }) { Text("Accept & download") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
    if (removal) AlertDialog(onDismissRequest = { removal = false }, title = { Text("Remove ${selected.title}?") },
        text = { Text("Removes this pack and its partial files. You can download it again.") },
        confirmButton = { TextButton(onClick = { removal = false; remove() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removal = false }) { Text("Keep") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeading("Your models", "One complete pack at a time. All inference stays local.") }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(runtime.message, style = MaterialTheme.typography.bodyMedium)
                    Text(runtime.nativeInfo ?: "Checking native runtime", style = MaterialTheme.typography.bodySmall)
                    if (runtime.loaded) Text("Unload before selecting another pack. Context changes also require a reload.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(ModelCatalog.packs, key = { it.id }) { pack ->
            val chosen = pack.id == runtime.selectedId
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pack.title, style = MaterialTheme.typography.titleMedium)
                    Text("${pack.bytes / 1_000_000} MB · ${pack.capabilities.joinToString(" · ") { when (it) {
                        ModelCapability.TEXT -> "Text"; ModelCapability.PHOTO -> "Photo"; ModelCapability.VOICE_INPUT -> "Voice in"; ModelCapability.VOICE_OUTPUT -> "Speech out"
                    } }}", style = MaterialTheme.typography.bodySmall)
                    Text(if (pack.experimental) "Experimental phone-test integration" else "Text path emulator-tested", style = MaterialTheme.typography.labelMedium)
                    if (!chosen) {
                        Text(if (pack.id in runtime.installedIds) "Verified & installed" else "Not installed", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { select(pack.id) }, enabled = !runtime.busy && !runtime.loaded && !generating) { Text("Select pack") }
                    } else {
                        when {
                            runtime.phase == RuntimePhase.DOWNLOADING -> {
                                LinearProgressIndicator(progress = { (runtime.downloaded.toFloat() / pack.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text("${runtime.downloaded / 1_000_000} / ${pack.bytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = pause, enabled = !runtime.downloadPausePending) { Text(if (runtime.downloadPausePending) "Pausing…" else "Pause") }
                            }
                            runtime.busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            runtime.loaded -> OutlinedButton(onClick = unload, enabled = !generating) { Text("Unload model") }
                            runtime.installed -> Button(onClick = load, enabled = !generating) { Text("Load model") }
                            else -> Button(onClick = { consent = true }, enabled = runtime.nativeInfo != null && !generating) { Text(if (runtime.downloaded > 0) "Resume download" else "Download pack") }
                        }
                        if (!runtime.loaded && !runtime.busy) TextButton(onClick = { removal = true }, enabled = !generating) { Text("Remove stored files") }
                    }
                    TextButton(onClick = { uri.openUri(pack.licensePage) }) { Text("License & source") }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(theme: ThemeChoice, developer: Boolean, reduced: Boolean,
                         onTheme: (ThemeChoice) -> Unit, onDeveloper: (Boolean) -> Unit, onReduced: (Boolean) -> Unit,
                         limits: GenerationLimits, onLimits: (GenerationLimits) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeading("Make it yours", "A calmer space, set up your way.") }
        item {
            Eyebrow("GENERATION")
            Text("Context tokens", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GenerationLimits.contexts.forEach { n -> FilterChip(limits.context == n, { onLimits(GenerationLimits(n, limits.output.coerceAtMost(n / 2))) }, label = { Text("$n") }) }
            }
            Text("Unload and reload the model to apply a context change. Larger contexts can use more RAM.", style = MaterialTheme.typography.bodySmall)
            Text("Maximum response tokens", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GenerationLimits.outputs.filter { it <= limits.context / 2 }.forEach { n -> FilterChip(limits.output == n, { onLimits(limits.copy(output = n)) }, label = { Text("$n") }) }
            }
            Text("Applies to the next response. Input and output must fit the loaded context.", style = MaterialTheme.typography.bodySmall)
        }
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
