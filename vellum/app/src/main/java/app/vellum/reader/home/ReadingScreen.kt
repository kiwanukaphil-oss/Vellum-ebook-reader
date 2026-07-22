package app.vellum.reader.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.ReadingPositionEntity
import app.vellum.reader.core.theme.sharedCoverBounds
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import kotlin.math.roundToInt

data class ReadingShelfItem(val book: BookEntity, val position: ReadingPositionEntity?)

/** The "Reading" tab: most recently opened book as a hero, the rest below. */
class ReadingViewModel(app: VellumApp) : ViewModel() {
    val items = combine(
        app.bookDao.observeShelf(),
        app.bookDao.observePositions(),
    ) { shelf, positions ->
        val positionByBook = positions.associateBy { it.bookUuid }
        shelf.filter { it.lastOpenedAt != null }
            .sortedByDescending { it.lastOpenedAt }
            .map { ReadingShelfItem(it, positionByBook[it.uuid]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Book-level percent is only honest for page-addressed formats (PDF/comics);
 * EPUB progression is within-chapter, so EPUBs get a chapter label instead.
 */
private fun ReadingShelfItem.progressLabel(): String? {
    val position = position ?: return null
    return when (book.format) {
        "epub" -> "Chapter ${position.chapterIndex + 1}"
        else -> "${(position.progression * 100).roundToInt()}%"
    }
}

@Composable
fun ReadingScreen(onOpenBook: (BookEntity) -> Unit) {
    val app = LocalContext.current.applicationContext as VellumApp
    val viewModel: ReadingViewModel = viewModel { ReadingViewModel(app) }
    val items by viewModel.items.collectAsState()

    Scaffold { padding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing on the go", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open a book from your library and it will wait for you here.",
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Reading",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp),
                )
            }
            item { ContinueReadingHero(items.first(), onOpenBook) }
            if (items.size > 1) {
                item {
                    Text(
                        "ALSO ON THE GO",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items.drop(1).forEach { item ->
                    item(key = item.book.uuid) { ReadingRow(item, onOpenBook) }
                }
            }
        }
        }
    }
}

@Composable
private fun ContinueReadingHero(item: ReadingShelfItem, onOpenBook: (BookEntity) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onOpenBook(item.book) },
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            BookCover(item.book, width = 96.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.book.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.book.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.progressLabel()?.let { label ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    // Rust hairline — the reading signal, never the chrome blue.
                    if (item.book.format != "epub") {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { item.position?.progression?.toFloat() ?: 0f },
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onOpenBook(item.book) }) {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun ReadingRow(item: ReadingShelfItem, onOpenBook: (BookEntity) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenBook(item.book) }
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        BookCover(item.book, width = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.book.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.progressLabel()?.let { label ->
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun BookCover(book: BookEntity, width: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(topStart = 2.dp, topEnd = 5.dp, bottomEnd = 5.dp, bottomStart = 2.dp)
    Box(
        modifier = Modifier
            .width(width)
            .height(width * 3 / 2)
            .sharedCoverBounds(book.uuid)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val coverFile = book.coverPath?.let(::File)
        if (coverFile != null) {
            AsyncImage(
                model = coverFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                book.title.take(1),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
