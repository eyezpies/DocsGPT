package com.docsgpt.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.docsgpt.android.ui.settings.SettingsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DocsGPT") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    MessageCard(message)
                }
            }

            if (uiState.isStreaming) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            InputBar(
                input = uiState.input,
                isStreaming = uiState.isStreaming,
                onInputChanged = viewModel::onInputChanged,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopStreaming,
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            initialApiHost = settings.apiHost,
            initialToken = settings.token,
            onDismiss = { showSettings = false },
            onSave = { apiHost, token ->
                viewModel.updateApiHost(apiHost)
                viewModel.updateToken(token)
                showSettings = false
            },
        )
    }
}

@Composable
private fun MessageCard(message: ChatMessage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = message.question, style = MaterialTheme.typography.titleSmall)
            if (message.thought.isNotBlank()) {
                Text(
                    text = message.thought,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                text = message.answer.ifBlank { "…" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (message.sources.isNotEmpty()) {
                Text(
                    text = "Sources: " + message.sources.joinToString { it.title ?: it.source ?: "unknown" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    input: String,
    isStreaming: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask a question…") },
            enabled = !isStreaming,
        )
        Box(modifier = Modifier.padding(start = 8.dp)) {
            if (isStreaming) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
