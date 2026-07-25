package app.vellum.reader.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vellum.reader.core.data.AiMetadataSuggestionEntity
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.theme.Fraunces
import app.vellum.reader.librarian.AiLibrarian

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiLibrarianSheet(
    books: List<BookEntity>,
    suggestions: List<AiMetadataSuggestionEntity>,
    processing: Set<String>,
    signedIn: Boolean,
    status: String?,
    onOrganize: (Boolean) -> Unit,
    onApply: (String) -> Unit,
    onDismissSuggestion: (String) -> Unit,
    onUndo: (String) -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val booksById = books.associateBy { it.uuid }
    // Refreshes retain their audit history, but only the newest decision for
    // each book belongs in the active UI.
    val latestByBook = suggestions.distinctBy { it.bookUuid }
    val pending = latestByBook.filter { it.status == "pending" }
    val recentApplied = latestByBook.filter { it.status == "applied" }.take(5)
    val busy = processing.isNotEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Column {
                    Text("AI Librarian", style = MaterialTheme.typography.headlineSmall, fontFamily = Fraunces)
                    Text(
                        "Names books, discovers series, and shapes thoughtful shelves.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!signedIn) {
                        Text("Private by design", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sign in once so book samples can travel through Vellum’s protected backend. Your API key never enters the app.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onSignIn) { Text("Sign in to continue") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        busy -> "Curating your library…"
                                        pending.isNotEmpty() -> "${pending.size} ${if (pending.size == 1) "book needs" else "books need"} a quick look"
                                        else -> "Everything is in its place"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Vellum also creates useful series, author, and thematic collections—without one-book clutter.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp))
                            } else {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { onOrganize(false) }, enabled = !busy) {
                                Text("Organise library")
                            }
                            OutlinedButton(onClick = { onOrganize(true) }, enabled = !busy) {
                                Text("Refresh all")
                            }
                        }
                    }
                }
            }

            status?.let {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
            }

            if (pending.isNotEmpty()) {
                Text("REVIEW", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                pending.forEach { suggestion ->
                    SuggestionCard(
                        suggestion = suggestion,
                        originalTitle = booksById[suggestion.bookUuid]?.title ?: "Book",
                        onApply = { onApply(suggestion.uuid) },
                        onDismiss = { onDismissSuggestion(suggestion.uuid) },
                    )
                }
            }

            if (recentApplied.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "RECENTLY ORGANISED",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                recentApplied.forEach { suggestion ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    suggestion.appliedTitle ?: suggestion.proposedTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        suggestion.appliedCategory,
                                        AiLibrarian.decodeNames(suggestion.appliedGenresJson).take(2).joinToString(" · ")
                                            .takeIf(String::isNotBlank),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onUndo(suggestion.uuid) }) { Text("Undo") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionCard(
    suggestion: AiMetadataSuggestionEntity,
    originalTitle: String,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(originalTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(suggestion.proposedTitle, style = MaterialTheme.typography.titleLarge, fontFamily = Fraunces)
            Text(
                suggestion.proposedAuthor,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SuggestionChip(onClick = {}, label = { Text(suggestion.proposedCategory) })
                AiLibrarian.decodeNames(suggestion.proposedGenresJson).forEach { genre ->
                    SuggestionChip(onClick = {}, label = { Text(genre) })
                }
                suggestion.proposedSeriesName?.let { series ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                suggestion.proposedSeriesIndex?.let { "$series · ${it.cleanNumber()}" } ?: series,
                            )
                        },
                    )
                }
            }
            Text(
                suggestion.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(suggestion.confidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Not now") }
                Button(onClick = onApply) { Text("Use suggestion") }
            }
        }
    }
}

private fun Float.cleanNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()
