package app.vellum.reader.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vellum.reader.core.fonts.VellumFonts
import app.vellum.reader.core.model.ReadingTheme
import app.vellum.reader.core.settings.ReaderSettings
import app.vellum.reader.core.settings.ReaderSettingsStore
import app.vellum.reader.core.settings.TurnStyle
import kotlinx.coroutines.launch

/**
 * Reading controls: theme, font, size, line spacing, margins, paragraph
 * spacing, and evening mode. Sliders commit on release so each drag doesn't
 * trigger a full re-pagination; font chips preview their own typeface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    store: ReaderSettingsStore,
    onOpenVoices: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val typography = settings.typography
    var pendingFontSize by remember(typography.fontSizeSp) { mutableFloatStateOf(typography.fontSizeSp) }
    var pendingLineHeight by remember(typography.lineHeightMultiplier) { mutableFloatStateOf(typography.lineHeightMultiplier) }
    var pendingMargin by remember(typography.pageMarginDp) { mutableFloatStateOf(typography.pageMarginDp) }
    var pendingParagraph by remember(typography.paragraphSpacingDp) { mutableFloatStateOf(typography.paragraphSpacingDp) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                ReadingTheme.All.forEach { theme ->
                    val selected = theme.id == settings.theme.id
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(theme.pageColor, CircleShape)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                shape = CircleShape,
                            )
                            .clickable { scope.launch { store.setTheme(theme.id) } },
                    )
                }
            }

            Text("Font", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                VellumFonts.All.forEach { font ->
                    val selected = font.id == typography.fontId
                    Box(
                        modifier = Modifier
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { scope.launch { store.setFont(font.id) } }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = font.label, fontFamily = font.family, fontSize = 15.sp)
                    }
                }
            }

            SliderRow(
                label = "Text size · ${pendingFontSize.toInt()}sp",
                value = pendingFontSize,
                onChange = { pendingFontSize = it },
                onCommit = { scope.launch { store.setFontSize(pendingFontSize) } },
                range = 14f..26f,
                steps = 11,
            )
            SliderRow(
                label = "Line spacing · ${"%.2f".format(pendingLineHeight)}",
                value = pendingLineHeight,
                onChange = { pendingLineHeight = it },
                onCommit = { scope.launch { store.setLineHeight(pendingLineHeight) } },
                range = 1.2f..2.0f,
                steps = 7,
            )
            SliderRow(
                label = "Margins · ${pendingMargin.toInt()}dp",
                value = pendingMargin,
                onChange = { pendingMargin = it },
                onCommit = { scope.launch { store.setPageMargin(pendingMargin) } },
                range = 16f..40f,
                steps = 11,
            )
            SliderRow(
                label = "Paragraph spacing · ${pendingParagraph.toInt()}dp",
                value = pendingParagraph,
                onChange = { pendingParagraph = it },
                onCommit = { scope.launch { store.setParagraphSpacing(pendingParagraph) } },
                range = 0f..16f,
                steps = 7,
            )

            Text("Page turn", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                listOf(TurnStyle.CURL to "Curl", TurnStyle.SLIDE to "Slide").forEach { (style, label) ->
                    val selected = settings.turnStyle == style
                    Box(
                        modifier = Modifier
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { scope.launch { store.setTurnStyle(style) } }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 15.sp)
                    }
                }
            }

            ToggleRow(
                title = "Evening mode",
                subtitle = "Gradually warms the page after 5pm",
                checked = settings.eveningMode,
                onChange = { scope.launch { store.setEveningMode(it) } },
            )
            ToggleRow(
                title = "Haptics",
                subtitle = "A soft tick on each page turn",
                checked = settings.hapticsEnabled,
                onChange = { scope.launch { store.setHaptics(it) } },
            )
            ToggleRow(
                title = "Paper texture",
                subtitle = "Subtle grain on light themes",
                checked = settings.paperTexture,
                onChange = { scope.launch { store.setPaperTexture(it) } },
            )
            ToggleRow(
                title = "Page edges",
                subtitle = "Read and unread page stacks at the sides",
                checked = settings.pageEdges,
                onChange = { scope.launch { store.setPageEdges(it) } },
            )
            ToggleRow(
                title = "Page rustle",
                subtitle = "A soft paper sound on page turns",
                checked = settings.pageRustle,
                onChange = { scope.launch { store.setPageRustle(it) } },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenVoices() }
                    .padding(top = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Voice & listening", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Reading engine and voice — no playback needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium)
            }

            Text("Focus timer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 10.dp),
            ) {
                listOf(0 to "Off", 15 to "15m", 25 to "25m", 45 to "45m").forEach { (minutes, label) ->
                    val selected = settings.focusMinutes == minutes
                    Box(
                        modifier = Modifier
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { scope.launch { store.setFocusMinutes(minutes) } }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onCommit,
        valueRange = range,
        steps = steps,
    )
}
