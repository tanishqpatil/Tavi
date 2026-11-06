package app.lfm3.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lfm3.probe.presentation.ProbeTurn
import app.lfm3.probe.presentation.ProbeViewModel
import app.lfm3.probe.diagnostics.AppLog
import app.lfm3.probe.models.ModelCatalog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog(applicationContext).record("APP_STARTED")
        enableEdgeToEdge()
        setContent { MaterialTheme { Surface { ProbeScreen() } } }
    }
}

@Composable
private fun ProbeScreen(viewModel: ProbeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(Section.CHAT) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LFM3 Probe", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Offline-first LFM3 feasibility build. No model pack is installed.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Section.entries.forEach { item ->
                AssistChip(onClick = { section = item }, label = { Text(item.label) })
            }
        }
        when (section) {
            Section.CHAT -> ChatSection(state, viewModel)
            Section.MODELS -> ModelSection()
            Section.SETTINGS -> SettingsSection()
        }
    }
}

private enum class Section(val label: String) { CHAT("Chat"), MODELS("Models"), SETTINGS("Settings") }

@Composable
private fun ColumnScope.ChatSection(state: app.lfm3.probe.presentation.ProbeUiState, viewModel: ProbeViewModel) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.turns, key = { "${it.id}-${it::class.simpleName}" }) { turn -> TurnCard(turn) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = viewModel::togglePhoto, label = { Text(if (state.photoAttached) "Photo attached" else "Attach photo") })
        AssistChip(onClick = viewModel::toggleAudio, label = { Text(if (state.audioAttached) "Audio attached" else "Record voice") })
    }
    OutlinedTextField(value = state.draft, onValueChange = viewModel::updateDraft, modifier = Modifier.fillMaxWidth(), label = { Text("Message") }, enabled = !state.isGenerating)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::submit, modifier = Modifier.weight(1f), enabled = !state.isGenerating) { Text("Send") }
        TextButton(onClick = viewModel::cancel, enabled = state.isGenerating) { Text("Stop") }
    }
}

@Composable
private fun ColumnScope.ModelSection() {
    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ModelCatalog.packs, key = { it.id }) { pack ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pack.title, style = MaterialTheme.typography.titleMedium)
                    Text(pack.capabilities.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text(pack.reason, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {}, enabled = false) { Text("Download unavailable") }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SettingsSection() {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text("Inference: local only. Network is reserved for user-initiated verified model downloads.")
        Text("Diagnostics: app events are stored privately without prompt, photo, audio, URI, or path content.")
        Text("Voice and camera controls are not connected until a qualified LFM3 engine is selected.")
    }
}

@Composable
private fun TurnCard(turn: ProbeTurn) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (turn) {
                is ProbeTurn.User -> {
                    Text("You", style = MaterialTheme.typography.labelLarge)
                    if (turn.text.isNotBlank()) Text(turn.text)
                    if (turn.includesPhoto || turn.includesAudio) {
                        Text(listOfNotNull(if (turn.includesPhoto) "fixture photo" else null, if (turn.includesAudio) "fixture audio" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is ProbeTurn.Assistant -> {
                    Text("Probe", style = MaterialTheme.typography.labelLarge)
                    Text(turn.text.ifBlank { "Generating…" })
                }
            }
        }
    }
}
