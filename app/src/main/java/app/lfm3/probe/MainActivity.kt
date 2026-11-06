package app.lfm3.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lfm3.probe.presentation.ProbeTurn
import app.lfm3.probe.presentation.ProbeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Surface { ProbeScreen() } } }
    }
}

@Composable
private fun ProbeScreen(viewModel: ProbeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LFM3 Probe", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Deterministic local development adapter — no model, microphone, photo, or network access.",
            style = MaterialTheme.typography.bodySmall,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.turns, key = { "${it.id}-${it::class.simpleName}" }) { turn -> TurnCard(turn) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = viewModel::togglePhoto, label = { Text(if (state.photoAttached) "Photo attached" else "Add fixture photo") })
            AssistChip(onClick = viewModel::toggleAudio, label = { Text(if (state.audioAttached) "Audio attached" else "Add fixture audio") })
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::updateDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            enabled = !state.isGenerating,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::submit, modifier = Modifier.weight(1f), enabled = !state.isGenerating) { Text("Run probe") }
            TextButton(onClick = viewModel::cancel, enabled = state.isGenerating) { Text("Stop") }
        }
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
