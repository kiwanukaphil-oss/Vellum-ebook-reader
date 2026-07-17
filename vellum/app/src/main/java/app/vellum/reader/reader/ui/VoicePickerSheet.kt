package app.vellum.reader.reader.ui

import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.vellum.reader.reader.tts.KokoroVoicePack

/**
 * Listening voice controls: pick the engine (system speech vs the on-device
 * Kokoro neural model), then a voice within it. Tapping a voice speaks a
 * sample. The Kokoro pack is a one-time ~305MB download into app storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    engine: String,
    systemVoices: List<Voice>,
    currentSystemVoice: String?,
    kokoroInstalled: Boolean,
    kokoroDownloadProgress: Float?,
    currentKokoroVoice: Int,
    onEngine: (String) -> Unit,
    onDownloadKokoro: () -> Unit,
    onPickSystemVoice: (Voice) -> Unit,
    onPickKokoroVoice: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "Voice",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            // Engine choice ------------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEngine("system") }
                    .padding(horizontal = 24.dp, vertical = 6.dp),
            ) {
                RadioButton(selected = engine == "system", onClick = { onEngine("system") })
                Column {
                    Text("System speech", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "This phone's speech engine · word-level highlight",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = kokoroInstalled) { onEngine("kokoro") }
                    .padding(horizontal = 24.dp, vertical = 6.dp),
            ) {
                RadioButton(
                    selected = engine == "kokoro",
                    onClick = { onEngine("kokoro") },
                    enabled = kokoroInstalled,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kokoro neural", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            kokoroInstalled -> "On-device neural voices · sentence highlight"
                            kokoroDownloadProgress != null ->
                                "Downloading… ${(kokoroDownloadProgress * 100).toInt()}%"
                            else -> "One-time 305MB download, then fully offline"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!kokoroInstalled && kokoroDownloadProgress == null) {
                    TextButton(onClick = onDownloadKokoro) { Text("Download") }
                }
            }
            kokoroDownloadProgress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Voices of the active engine ---------------------------------
            Text(
                "Tap a voice to hear a sample.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (engine == "kokoro" && kokoroInstalled) {
                LazyColumn {
                    items(KokoroVoicePack.VOICES.size) { sid ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickKokoroVoice(sid) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                KokoroVoicePack.VOICES[sid],
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (sid == currentKokoroVoice) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            } else {
                if (systemVoices.isEmpty()) {
                    Text(
                        "No offline voices reported — check the phone's text-to-speech settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn {
                    items(systemVoices, key = { it.name }) { voice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickSystemVoice(voice) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(prettyVoiceLabel(voice), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    voice.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (voice.name == currentSystemVoice) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
        }
    }
}

/** "English (United States) · high quality" beats "en-us-x-tpf-local". */
private fun prettyVoiceLabel(voice: Voice): String {
    val quality = when {
        voice.quality >= Voice.QUALITY_VERY_HIGH -> "highest quality"
        voice.quality >= Voice.QUALITY_HIGH -> "high quality"
        voice.quality >= Voice.QUALITY_NORMAL -> "standard"
        else -> "basic"
    }
    return "${voice.locale.displayName} · $quality"
}
