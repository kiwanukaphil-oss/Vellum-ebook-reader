package app.vellum.reader.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.vellum.reader.core.data.AnnotationEntity
import app.vellum.reader.core.model.HighlightColors

/**
 * Floating actions for an active selection: highlight colors, note, copy,
 * and the in-context lookups. Sits above the bottom edge so it never covers
 * the selected line.
 */
@Composable
fun SelectionToolbar(
    onHighlight: (colorId: String) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onWikipedia: () -> Unit,
    onTranslate: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                HighlightColors.All.forEach { highlight ->
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(highlight.color, CircleShape)
                            .border(1.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
                            .clickable { onHighlight(highlight.id) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onNote) { Text("Note") }
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onDefine) { Text("Define") }
                TextButton(onClick = onWikipedia) { Text("Wiki") }
                TextButton(onClick = onTranslate) { Text("Translate") }
            }
        }
    }
}

/** Editor for a new note (annotation == null) or an existing annotation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationEditorSheet(
    quote: String,
    annotation: AnnotationEntity?,
    onSave: (colorId: String, note: String?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var colorId by remember { mutableStateOf(annotation?.colorId ?: HighlightColors.All.first().id) }
    var note by remember { mutableStateOf(annotation?.note ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "“${quote.take(220)}${if (quote.length > 220) "…" else ""}”",
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HighlightColors.All.forEach { highlight ->
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(highlight.color, CircleShape)
                            .border(
                                width = if (highlight.id == colorId) 3.dp else 1.dp,
                                color = if (highlight.id == colorId) MaterialTheme.colorScheme.primary
                                else Color.Black.copy(alpha = 0.15f),
                                shape = CircleShape,
                            )
                            .clickable { colorId = highlight.id },
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Margin note") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (onDelete != null) {
                    TextButton(onClick = { onDelete(); onDismiss() }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Box {}
                }
                TextButton(onClick = { onSave(colorId, note); onDismiss() }) { Text("Save") }
            }
        }
    }
}

/** Every highlight and note in the book; tapping one jumps to its page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsListSheet(
    bookTitle: String,
    annotations: List<AnnotationEntity>,
    onJump: (AnnotationEntity) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Highlights & notes", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                TextButton(onClick = onExport, enabled = annotations.isNotEmpty()) { Text("Export") }
            }
            if (annotations.isEmpty()) {
                Text(
                    "Nothing yet — long-press a word in the text to begin.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn {
                    items(annotations, key = { it.uuid }) { annotation ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJump(annotation) }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(44.dp)
                                    .background(HighlightColors.byId(annotation.colorId).color, RoundedCornerShape(2.dp)),
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = annotation.quote.take(140),
                                    fontFamily = FontFamily.Serif,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                )
                                annotation.note?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                Text(
                                    text = "Chapter ${annotation.chapterIndex + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Dictionary / Wikipedia result, fetched on open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookupSheet(
    title: String,
    term: String,
    fetch: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    var result by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(term) {
        result = fetch(term)
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("$title · “$term”", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                result == null -> Text("No entry found (or no connection).", style = MaterialTheme.typography.bodyMedium)
                else -> Text(result!!, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
