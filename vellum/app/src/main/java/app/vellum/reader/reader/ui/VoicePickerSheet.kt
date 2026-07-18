package app.vellum.reader.reader.ui

import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vellum.reader.core.settings.ElevenLabsModel
import app.vellum.reader.core.settings.NarrationProvider
import app.vellum.reader.reader.tts.ElevenLabsVoice
import app.vellum.reader.reader.tts.KokoroVoicePack
import java.text.NumberFormat
import java.util.Locale

/** Provider-owned narration settings. Every voice is visibly tied to its source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    provider: NarrationProvider,
    systemVoices: List<Voice>,
    currentSystemVoice: String?,
    kokoroInstalled: Boolean,
    kokoroDownloadProgress: Float?,
    currentKokoroVoice: Int,
    elevenLabs: ElevenLabsUiState,
    elevenLabsCache: ElevenLabsCacheStatus,
    currentElevenLabsVoiceId: String?,
    currentElevenLabsVoiceName: String?,
    elevenLabsModel: ElevenLabsModel,
    onProvider: (NarrationProvider) -> Unit,
    onDownloadKokoro: () -> Unit,
    onPickSystemVoice: (Voice) -> Unit,
    onPickKokoroVoice: (Int) -> Unit,
    onConnectElevenLabs: (String) -> Unit,
    onDisconnectElevenLabs: () -> Unit,
    onRefreshElevenLabs: () -> Unit,
    onPickElevenLabsVoice: (ElevenLabsVoice) -> Unit,
    onElevenLabsModel: (ElevenLabsModel) -> Unit,
    onClearElevenLabsCache: () -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var confirmClearCache by remember { mutableStateOf(false) }
    LaunchedEffect(elevenLabs.connected) {
        if (elevenLabs.connected) apiKey = ""
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .padding(bottom = 24.dp),
        ) {
            item(key = "heading") {
                Column {
                    Text(
                        "Voice & narration",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Serif,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Text(
                        selectedSummary(
                            provider,
                            systemVoices,
                            currentSystemVoice,
                            currentKokoroVoice,
                            currentElevenLabsVoiceName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    Text(
                        "Narration provider",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            item(key = "provider-system") {
                ProviderChoice(
                    selected = provider == NarrationProvider.SYSTEM,
                    title = "System speech",
                    subtitle = "Voices supplied by this phone",
                    badge = "Offline",
                    onClick = { onProvider(NarrationProvider.SYSTEM) },
                )
            }
            item(key = "provider-kokoro") {
                ProviderChoice(
                    selected = provider == NarrationProvider.KOKORO,
                    title = "Kokoro neural",
                    subtitle = "Vellum's on-device neural narrator",
                    badge = if (kokoroInstalled) "Offline" else "Setup",
                    onClick = { onProvider(NarrationProvider.KOKORO) },
                )
            }
            item(key = "provider-elevenlabs") {
                ProviderChoice(
                    selected = provider == NarrationProvider.ELEVENLABS,
                    title = "ElevenLabs premium",
                    subtitle = "Cloud narrators from your account",
                    badge = if (elevenLabs.connected) "Connected" else "Cloud",
                    onClick = { onProvider(NarrationProvider.ELEVENLABS) },
                )
            }
            item(key = "provider-divider") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            }

            when (provider) {
                NarrationProvider.SYSTEM -> systemVoiceItems(
                    voices = systemVoices,
                    currentVoice = currentSystemVoice,
                    onPick = onPickSystemVoice,
                )
                NarrationProvider.KOKORO -> kokoroVoiceItems(
                    installed = kokoroInstalled,
                    downloadProgress = kokoroDownloadProgress,
                    currentVoice = currentKokoroVoice,
                    onDownload = onDownloadKokoro,
                    onPick = onPickKokoroVoice,
                )
                NarrationProvider.ELEVENLABS -> elevenLabsItems(
                    state = elevenLabs,
                    cache = elevenLabsCache,
                    apiKey = apiKey,
                    onApiKey = { apiKey = it },
                    currentVoiceId = currentElevenLabsVoiceId,
                    model = elevenLabsModel,
                    onConnect = { onConnectElevenLabs(apiKey) },
                    onDisconnect = onDisconnectElevenLabs,
                    onRefresh = onRefreshElevenLabs,
                    onPickVoice = onPickElevenLabsVoice,
                    onModel = onElevenLabsModel,
                    onClearCache = { confirmClearCache = true },
                )
            }
            item(key = "bottom-space") { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("Clear cached narration?") },
            text = {
                Text(
                    "This removes all ElevenLabs audio stored on this phone. " +
                        "Listening again will regenerate it and use credits.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCache = false
                        onClearElevenLabsCache()
                    },
                ) { Text("Clear audio") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProviderChoice(
    selected: Boolean,
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProviderBadge(badge)
        }
    }
}

private fun LazyListScope.systemVoiceItems(
    voices: List<Voice>,
    currentVoice: String?,
    onPick: (Voice) -> Unit,
) {
    item(key = "system-heading") {
        PanelHeading("Voices from your phone", "Android system speech · word-level highlight")
    }
    if (voices.isEmpty()) {
        item(key = "system-empty") {
            EmptyVoiceMessage("No offline system voices reported. Check the phone's text-to-speech settings.")
        }
        return
    }
    items(voices, key = { "system:${it.name}" }) { voice ->
        VoiceRow(
            title = prettyVoiceLabel(voice),
            description = voice.name,
            badge = "Phone",
            selected = voice.name == currentVoice,
            onClick = { onPick(voice) },
        )
    }
}

private fun LazyListScope.kokoroVoiceItems(
    installed: Boolean,
    downloadProgress: Float?,
    currentVoice: Int,
    onDownload: () -> Unit,
    onPick: (Int) -> Unit,
) {
    item(key = "kokoro-heading") {
        PanelHeading("Offline Kokoro voices", "Neural narration generated entirely on this device")
    }
    if (!installed) {
        item(key = "kokoro-setup") {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("Download the 305MB voice pack once. No book text leaves your phone.")
                if (downloadProgress == null) {
                    Button(onClick = onDownload, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Download Kokoro")
                    }
                } else {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        "Downloading… ${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        return
    }
    items(KokoroVoicePack.VOICES.size, key = { "kokoro:$it" }) { sid ->
        VoiceRow(
            title = KokoroVoicePack.VOICES[sid],
            description = "On-device neural voice",
            badge = "Kokoro",
            selected = sid == currentVoice,
            onClick = { onPick(sid) },
        )
    }
}

private fun LazyListScope.elevenLabsItems(
    state: ElevenLabsUiState,
    cache: ElevenLabsCacheStatus,
    apiKey: String,
    onApiKey: (String) -> Unit,
    currentVoiceId: String?,
    model: ElevenLabsModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onPickVoice: (ElevenLabsVoice) -> Unit,
    onModel: (ElevenLabsModel) -> Unit,
    onClearCache: () -> Unit,
) {
    item(key = "elevenlabs-heading") {
        PanelHeading("Voices from ElevenLabs", "Premium cloud narration · internet and credits required")
    }
    if (!state.connected) {
        item(key = "elevenlabs-connect") {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    "Connect a restricted personal API key. Passages you narrate will be sent to ElevenLabs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKey,
                    label = { Text("ElevenLabs API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Button(
                    onClick = onConnect,
                    enabled = apiKey.isNotBlank() && !state.loading,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(if (state.loading) "Connecting…" else "Connect ElevenLabs")
                }
                if (state.loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
        }
        return
    }

    item(key = "elevenlabs-account") {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            state.subscription?.let { subscription ->
                val number = NumberFormat.getIntegerInstance()
                Text(
                    "${number.format(subscription.creditsRemaining)} credits remaining · ${subscription.tier}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quality", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(10.dp))
                FilterChip(
                    selected = model == ElevenLabsModel.PREMIUM,
                    onClick = { onModel(ElevenLabsModel.PREMIUM) },
                    label = { Text("Premium") },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = model == ElevenLabsModel.EFFICIENT,
                    onClick = { onModel(ElevenLabsModel.EFFICIENT) },
                    label = { Text("Efficient") },
                )
            }
            Text(
                if (model == ElevenLabsModel.PREMIUM) {
                    "Best long-form consistency · standard credit usage"
                } else {
                    "Lower latency · approximately half-price generation"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    currentVoiceId == null -> "Choose a narrator to calculate chapter cache coverage"
                    cache.chapterFullyCached -> "Current chapter cached · replay uses no credits"
                    cache.totalPassages > 0 ->
                        "${cache.cachedPassages} of ${cache.totalPassages} current-chapter passages cached"
                    else -> "No narration cached for the current chapter"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (cache.chapterFullyCached) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Stored on this phone: ${formatStorageSize(cache.storedBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = onClearCache,
                enabled = cache.storedBytes > 0L,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("Clear cached audio") }
            Row {
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh account") }
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
    if (state.loading) {
        item(key = "elevenlabs-loading") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
    }
    if (state.voices.isEmpty() && !state.loading) {
        item(key = "elevenlabs-empty") {
            EmptyVoiceMessage("No voices were returned for this ElevenLabs account.")
        }
        return
    }
    items(state.voices, key = { "elevenlabs:${it.id}" }) { voice ->
        val attributes = listOfNotNull(voice.accent, voice.gender, voice.useCase?.replace('_', ' '))
            .joinToString(" · ")
            .ifBlank { voice.description ?: voice.category }
        VoiceRow(
            title = voice.name,
            description = attributes,
            badge = "ElevenLabs",
            selected = voice.id == currentVoiceId,
            onClick = { onPickVoice(voice) },
        )
    }
}

private fun formatStorageSize(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    bytes < 1_073_741_824L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
}

@Composable
private fun PanelHeading(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyVoiceMessage(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
}

@Composable
private fun VoiceRow(
    title: String,
    description: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ProviderBadge(badge)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
}

@Composable
private fun ProviderBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun selectedSummary(
    provider: NarrationProvider,
    systemVoices: List<Voice>,
    currentSystemVoice: String?,
    kokoroVoice: Int,
    elevenLabsVoiceName: String?,
): String = when (provider) {
    NarrationProvider.SYSTEM -> {
        val voice = systemVoices.firstOrNull { it.name == currentSystemVoice }
        "Selected · System speech · ${voice?.let(::prettyVoiceLabel) ?: "Phone default"}"
    }
    NarrationProvider.KOKORO ->
        "Selected · Kokoro · ${KokoroVoicePack.VOICES.getOrElse(kokoroVoice) { "Default" }}"
    NarrationProvider.ELEVENLABS ->
        "Selected · ElevenLabs · ${elevenLabsVoiceName ?: "Choose a narrator"}"
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
