package app.vellum.reader.notes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.AnnotationEntity
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.model.HighlightColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NotesBookGroup(val book: BookEntity, val annotations: List<AnnotationEntity>)

/**
 * The Notes tab: every highlight and note in the library, grouped by book,
 * newest-annotated book first. Annotations stay anchored to the words, so a
 * tap can reopen the exact passage.
 */
class NotesViewModel(app: VellumApp) : ViewModel() {
    val groups = combine(
        app.annotationDao.observeAllLive(),
        app.bookDao.observeShelf(),
    ) { annotations, shelf ->
        val booksByUuid = shelf.associateBy { it.uuid }
        annotations.groupBy { it.bookUuid }
            .mapNotNull { (bookUuid, rows) -> booksByUuid[bookUuid]?.let { NotesBookGroup(it, rows) } }
            .sortedByDescending { group -> group.annotations.maxOf { it.createdAt } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** One book's annotations as shareable Markdown (quote, note, chapter). */
private fun NotesBookGroup.toMarkdown(): String = buildString {
    append("# ").append(book.title)
    if (book.author.isNotBlank()) append(" — ").append(book.author)
    append("\n")
    annotations.forEach { annotation ->
        append("\n> ").append(annotation.quote.trim()).append("\n")
        annotation.note?.takeIf { it.isNotBlank() }?.let { append("\n").append(it.trim()).append("\n") }
        append("\n— Chapter ").append(annotation.chapterIndex + 1).append("\n")
    }
}

@Composable
fun NotesScreen(onOpenPassage: (bookUuid: String, chapter: Int, offset: Int) -> Unit) {
    val app = LocalContext.current.applicationContext as VellumApp
    val viewModel: NotesViewModel = viewModel { NotesViewModel(app) }
    val groups by viewModel.groups.collectAsState()
    val context = LocalContext.current

    Scaffold { padding ->
        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No notes yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Select any passage while reading to highlight it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight().align(Alignment.TopCenter),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Notes",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp),
                )
            }
            groups.forEach { group ->
                item(key = "book-${group.book.uuid}") {
                    BookHeader(
                        group = group,
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, group.toMarkdown())
                            context.startActivity(Intent.createChooser(send, "Share notes"))
                        },
                    )
                }
                group.annotations.forEach { annotation ->
                    item(key = annotation.uuid) {
                        AnnotationCard(
                            annotation = annotation,
                            onClick = { onOpenPassage(group.book.uuid, annotation.chapterIndex, annotation.startChar) },
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun BookHeader(group: NotesBookGroup, onShare: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                group.book.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val count = group.annotations.size
            Text(
                "${group.book.author} · $count ${if (count == 1) "highlight" else "highlights"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShare) {
            Icon(
                Icons.Filled.Share,
                contentDescription = "Share ${group.book.title} notes as Markdown",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AnnotationCard(annotation: AnnotationEntity, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // The highlight ink as a left rule — the card's only color.
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(HighlightColors.byId(annotation.colorId).color),
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "“${annotation.quote.trim()}”",
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                annotation.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "CH. ${annotation.chapterIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
