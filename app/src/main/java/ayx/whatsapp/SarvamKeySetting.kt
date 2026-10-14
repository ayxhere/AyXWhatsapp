package ayx.whatsapp

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Sarvam API Key settings UI.
 * Call this from VoicePickerScreen or any Settings page:
 *
 *   SarvamKeySetting()
 */
@Composable
fun SarvamKeySetting() {
    val ctx = LocalContext.current
    var aiKey by remember { mutableStateOf(SarvamTts.getSavedApiKey(ctx)) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Sarvam AI Voice (.vv)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Type .vv or /voice followed by text to send it as voice message.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = if (showKey) aiKey else "•".repeat(aiKey.length.coerceAtMost(24)),
            onValueChange = { aiKey = it; saved = false },
            label = { Text("Sarvam API Key") },
            placeholder = { Text("Paste key from sarvam.ai") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = "toggle visibility"
                    )
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                SarvamTts.saveApiKey(ctx, aiKey.trim())
                saved = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saved) "Saved ✓" else "Save Key")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Get your free key: sarvam.ai → Dashboard → API Keys",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
