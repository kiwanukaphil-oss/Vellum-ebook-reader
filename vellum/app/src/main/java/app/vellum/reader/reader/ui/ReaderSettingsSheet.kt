package app.vellum.reader.reader.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vellum.reader.core.fonts.VellumFonts
import app.vellum.reader.core.model.ReadingTheme
import app.vellum.reader.core.settings.ReaderSettings
import app.vellum.reader.core.settings.ReaderSettingsStore
import app.vellum.reader.core.settings.TurnStyle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Reading controls, Ink & Linen edition: a live specimen up top (the modal
 * sheet hides the page, so the preview must live inside it), then quiet
 * sections — Theme / Typeface / Metrics / Page / Light / Sound & focus.
 * Steppers commit per tap; only brightness drags continuously.
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
    val font = VellumFonts.byId(typography.fontId)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
        ) {
            Text("Reading", style = MaterialTheme.typography.titleLarge)
            Text(
                "Changes apply to the page instantly.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Live preview ------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .background(settings.theme.pageColor, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text(
                        "The typography is the product. Publisher CSS is a guest — welcome, but seated where we say.",
                        fontFamily = font.family,
                        fontSize = typography.fontSizeSp.sp,
                        lineHeight = (typography.fontSizeSp * typography.lineHeightMultiplier).sp,
                        color = settings.theme.inkColor,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textAlign = TextAlign.Justify,
                            hyphens = Hyphens.Auto,
                            lineBreak = LineBreak.Paragraph,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${font.label} · ${typography.fontSizeSp.toInt()}sp".uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            color = settings.theme.inkColor.copy(alpha = 0.55f),
                        )
                        Text(
                            "JUSTIFIED · HYPHENATED",
                            style = MaterialTheme.typography.labelSmall,
                            color = settings.theme.inkColor.copy(alpha = 0.55f),
                        )
                    }
                }
            }

            // ---- Theme -------------------------------------------------------
            SectionTitle("In-book theme")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingTheme.All.forEach { theme ->
                    val selected = theme.id == settings.theme.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { scope.launch { store.setTheme(theme.id) } },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .background(theme.pageColor, RoundedCornerShape(12.dp)),
                        ) {
                            Text("Aa", color = theme.inkColor, fontSize = 18.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            theme.label.uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- Typeface ----------------------------------------------------
            SectionTitle("Typeface")
            VellumFonts.All.forEach { candidate ->
                val selected = candidate.id == typography.fontId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(11.dp),
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(11.dp))
                        .clickable { scope.launch { store.setFont(candidate.id) } }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        candidate.label,
                        fontFamily = candidate.family,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
                    }
                }
            }

            // ---- Metrics -----------------------------------------------------
            SectionTitle("Metrics")
            StepperRow(
                label = "Size",
                display = "${typography.fontSizeSp.toInt()} sp",
                onDecrement = { scope.launch { store.setFontSize((typography.fontSizeSp - 1f).coerceAtLeast(14f)) } },
                onIncrement = { scope.launch { store.setFontSize((typography.fontSizeSp + 1f).coerceAtMost(26f)) } },
            )
            StepperRow(
                label = "Line height",
                display = String.format(Locale.US, "%.2f", typography.lineHeightMultiplier),
                onDecrement = {
                    scope.launch { store.setLineHeight((typography.lineHeightMultiplier - 0.05f).coerceAtLeast(1.2f)) }
                },
                onIncrement = {
                    scope.launch { store.setLineHeight((typography.lineHeightMultiplier + 0.05f).coerceAtMost(2.0f)) }
                },
            )
            StepperRow(
                label = "Margins",
                display = marginLabel(typography.pageMarginDp),
                onDecrement = { scope.launch { store.setPageMargin((typography.pageMarginDp - 4f).coerceAtLeast(16f)) } },
                onIncrement = { scope.launch { store.setPageMargin((typography.pageMarginDp + 4f).coerceAtMost(40f)) } },
            )
            StepperRow(
                label = "Paragraph space",
                display = "${typography.paragraphSpacingDp.toInt()} dp",
                onDecrement = {
                    scope.launch { store.setParagraphSpacing((typography.paragraphSpacingDp - 2f).coerceAtLeast(0f)) }
                },
                onIncrement = {
                    scope.launch { store.setParagraphSpacing((typography.paragraphSpacingDp + 2f).coerceAtMost(16f)) }
                },
            )

            // ---- Page --------------------------------------------------------
            SectionTitle("Page turn")
            val styles = listOf(TurnStyle.CURL to "Curl", TurnStyle.SLIDE to "Slide", TurnStyle.FADE to "Fade")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                styles.forEachIndexed { index, (style, label) ->
                    SegmentedButton(
                        selected = settings.turnStyle == style,
                        onClick = { scope.launch { store.setTurnStyle(style) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = styles.size),
                    ) { Text(label) }
                }
            }
            ToggleRow(
                title = "Haptic on turn",
                subtitle = "A soft tick as the paper lifts",
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
                subtitle = "Read and unread stacks at the sides",
                checked = settings.pageEdges,
                onChange = { scope.launch { store.setPageEdges(it) } },
            )
            ToggleRow(
                title = "Page rustle",
                subtitle = "A soft paper sound on turns",
                checked = settings.pageRustle,
                onChange = { scope.launch { store.setPageRustle(it) } },
            )

            // ---- Light -------------------------------------------------------
            SectionTitle("Light")
            ToggleRow(
                title = "Follow the sun",
                subtitle = "Warms the page toward candlelight after dusk",
                checked = settings.eveningMode,
                onChange = { scope.launch { store.setEveningMode(it) } },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(6.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFF5F0E5), Color(0xFFF0E4C8), Color(0xFFE9C79A), Color(0xFFE0A86A)),
                        ),
                        RoundedCornerShape(3.dp),
                    ),
            )
            BrightnessRow(settings = settings, store = store)

            // ---- Sound & focus ----------------------------------------------
            SectionTitle("Sound & focus")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenVoices() }
                    .padding(vertical = 6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Voice & listening", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Reading engine and voice",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Focus timer",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 10.dp),
            )
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
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { scope.launch { store.setFocusMinutes(minutes) } }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Named margin widths read better than raw dp on a stepper. */
private fun marginLabel(dp: Float): String = when {
    dp <= 20f -> "Compact"
    dp <= 28f -> "Cozy"
    dp <= 34f -> "Roomy"
    else -> "Airy"
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(top = 20.dp),
    )
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 12.dp),
    )
}

@Composable
private fun StepperRow(
    label: String,
    display: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(9.dp)),
        ) {
            Text(
                "−",
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(onClick = onDecrement)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Text(
                display,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 64.dp),
            )
            Text(
                "+",
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(onClick = onIncrement)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Reader brightness: drags preview live against the window; release persists.
 * "Auto" clears the override back to the system level.
 */
@Composable
private fun BrightnessRow(settings: ReaderSettings, store: ReaderSettingsStore) {
    val scope = rememberCoroutineScope()
    val window = (LocalContext.current as? Activity)?.window
    var pending by remember(settings.readerBrightness) {
        mutableFloatStateOf(settings.readerBrightness)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Text("Brightness", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(16.dp))
        Slider(
            value = if (pending < 0f) 1f else pending,
            onValueChange = { value ->
                pending = value.coerceIn(0.05f, 1f)
                window?.let { it.attributes = it.attributes.apply { screenBrightness = pending } }
            },
            onValueChangeFinished = { scope.launch { store.setReaderBrightness(pending) } },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = pending >= 0f,
            onClick = {
                pending = -1f
                window?.let {
                    it.attributes = it.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
                scope.launch { store.setReaderBrightness(-1f) }
            },
        ) { Text(if (pending < 0f) "Auto" else "Reset") }
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
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
