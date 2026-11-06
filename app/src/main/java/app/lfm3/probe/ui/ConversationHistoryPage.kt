package app.lfm3.probe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lfm3.probe.history.ConversationSummary
import app.lfm3.probe.presentation.ProbeUiState
import app.lfm3.probe.presentation.ProbeViewModel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConversationHistoryPage(state: ProbeUiState, model: ProbeViewModel, onOpen: () -> Unit) {
    var selected by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
    var title by remember { mutableStateOf("") }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        exporting?.let { id -> if (uri != null) model.exportConversation(id, uri) }; exporting = null
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.restoreConversation(uri) }
    val enabled = !state.isHistoryBusy && !state.isGenerating && !state.isRecording && !state.isImporting
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Your conversations", style = MaterialTheme.typography.headlineMedium)
            Text("Saved privately on this device, including photo and voice attachments.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(state.historyQuery, model::searchHistory, Modifier.fillMaxWidth(), label = { Text("Search titles and messages") }, singleLine = true)
            TextButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = enabled) { Text("Restore conversation archive") }
            Text("Exports contain private text and media. Automatic cloud backup is disabled; deleting a conversation removes its unshared attachments.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.isHistoryBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
        if (state.library.isEmpty()) item { Text(if (state.historyQuery.isBlank()) "Your first saved conversation will appear here." else "No matching conversations.") }
        items(state.library, key = { it.id }) { chat ->
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${chat.count} turns · ${if (chat.real) "Local model" else "Interface preview"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(chat.updated))}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.openConversation(chat.id); onOpen() }, enabled = enabled) { Text("Open") }
                        TextButton(onClick = { selected = chat; title = chat.title }, enabled = enabled) { Text("Rename") }
                        TextButton(onClick = { exporting = chat.id; save.launch("Tavi-conversation.zip") }, enabled = enabled && chat.count > 0) { Text("Export") }
                        TextButton(onClick = { deleting = chat }, enabled = enabled) { Text("Delete") }
                    }
                }
            }
        }
        if (state.hasMoreConversations) item { TextButton(onClick = model::moreConversations, enabled = enabled) { Text("Load more conversations") } }
    }
    selected?.let { chat -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Rename conversation") },
        text = { OutlinedTextField(title, { title = it.take(120) }, label = { Text("Title") }) },
        confirmButton = { TextButton(onClick = { model.renameConversation(chat.id, title); selected = null }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } }) }
    deleting?.let { chat -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${chat.title}?") },
        text = { Text("Permanently removes this conversation and attachments that no other saved conversation uses. Export first if you need a copy.") },
        confirmButton = { TextButton(onClick = { model.deleteConversation(chat.id); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
}
