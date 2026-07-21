package com.docsgpt.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(
    initialApiHost: String,
    initialToken: String?,
    onDismiss: () -> Unit,
    onSave: (apiHost: String, token: String) -> Unit,
) {
    var apiHost by remember { mutableStateOf(initialApiHost) }
    var token by remember { mutableStateOf(initialToken.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = apiHost,
                    onValueChange = { apiHost = it },
                    label = { Text("API host") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(apiHost.trim(), token.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
