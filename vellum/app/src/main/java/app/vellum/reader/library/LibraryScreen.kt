package app.vellum.reader.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.settings.ReaderSettings
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.absoluteValue

/**
 * The library: cover grid with filters and sort. Long-press enters selection
 * mode — a contextual bar offers Edit (single), Organize, and Remove (batch) —
 * so every shelf operation scales from one book to many.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenBook: (BookEntity) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenInsights: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as VellumApp
    val viewModel: LibraryViewModel = viewModel { LibraryViewModel(app) }
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val selectionMode = selected.isNotEmpty()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<BookEntity?>(null) }
    var organizeOpen by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var syncSheetOpen by remember { mutableStateOf(false) }
    val settings by app.settingsStore.settings.collectAsState(initial = ReaderSettings())
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch {
                app.settingsStore.setSyncFolder(uri.toString())
                viewModel.syncNow(uri.toString())
            }
        }
    }

    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importEpub)
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        if (selected.size == 1) {
                            IconButton(onClick = {
                                detailsFor = state.books.firstOrNull { it.uuid in selected }
                            }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit book")
                            }
                        }
                        IconButton(onClick = { organizeOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Collections and tags")
                        }
                        IconButton(onClick = { confirmBatchDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("Vellum", fontFamily = FontFamily.Serif) },
                    actions = {
                        IconButton(onClick = { syncSheetOpen = true }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Sync")
                        }
                        IconButton(onClick = onOpenInsights) {
                            Icon(Icons.Filled.Info, contentDescription = "Reading insights")
                        }
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "Search library")
                        }
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            ShelfSort.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(if (mode == state.sort) "✓ ${mode.label}" else mode.label) },
                                    onClick = {
                                        viewModel.setSort(mode)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = {
                    // Comic archives surface under many mimes depending on the
                    // file manager (rar/zip variants) — list them all so .cbr
                    // and .cbz are selectable; import sniffs the real format.
                    picker.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            "application/zip",
                            "application/x-rar-compressed",
                            "application/vnd.rar",
                            "application/rar",
                            "application/x-cbz",
                            "application/x-cbr",
                            "application/vnd.comicbook+zip",
                            "application/vnd.comicbook-rar",
                            "application/octet-stream",
                        ),
                    )
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add a book")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (!selectionMode && (state.collections.isNotEmpty() || state.tags.isNotEmpty())) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.filter == ShelfFilter.All,
                            onClick = { viewModel.setFilter(ShelfFilter.All) },
                            label = { Text("All") },
                        )
                    }
                    items(state.collections, key = { "c" + it.uuid }) { collection ->
                        FilterChip(
                            selected = (state.filter as? ShelfFilter.InCollection)?.collectionUuid == collection.uuid,
                            onClick = { viewModel.setFilter(ShelfFilter.InCollection(collection.uuid)) },
                            label = { Text(collection.name) },
                        )
                    }
                    items(state.tags, key = { "t" + it.uuid }) { tag ->
                        FilterChip(
                            selected = (state.filter as? ShelfFilter.WithTag)?.tagUuid == tag.uuid,
                            onClick = { viewModel.setFilter(ShelfFilter.WithTag(tag.uuid)) },
                            label = { Text("#${tag.name}") },
                        )
                    }
                }
            }

            if (state.books.isEmpty() && !state.importing) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "Your shelf is empty.\nTap + to add an EPUB or PDF.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(105.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(state.books, key = { it.uuid }) { book ->
                        BookCard(
                            book = book,
                            isSelected = book.uuid in selected,
                            onClick = {
                                if (selectionMode) viewModel.toggleSelection(book.uuid)
                                else onOpenBook(book)
                            },
                            onLongClick = { viewModel.toggleSelection(book.uuid) },
                        )
                    }
                }
            }
        }
    }

    detailsFor?.let { book ->
        BookDetailsSheet(
            book = book,
            state = state,
            viewModel = viewModel,
            onDismiss = { detailsFor = null },
        )
    }

    if (organizeOpen) {
        OrganizeSheet(
            state = state,
            selectedUuids = selected,
            viewModel = viewModel,
            onDismiss = { organizeOpen = false },
        )
    }

    if (syncSheetOpen) {
        SyncSheet(
            folderConfigured = settings.syncFolderUri != null,
            lastSyncAt = settings.lastSyncAt,
            status = syncStatus,
            syncing = syncing,
            onChooseFolder = { folderPicker.launch(null) },
            onSyncNow = { settings.syncFolderUri?.let(viewModel::syncNow) },
            onDismiss = { syncSheetOpen = false },
        )
    }

    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("Remove ${if (selected.size == 1) "this book" else "${selected.size} books"} from the library?") },
            text = { Text("Highlights, notes, and reading progress will be deleted. Original files on your device aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBooks(selected)
                    confirmBatchDelete = false
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            val coverFile = book.coverPath?.let { File(it) }?.takeIf { it.exists() }
            val coverModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    } else {
                        Modifier
                    },
                )
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = "Cover of ${book.title}",
                    contentScale = ContentScale.Crop,
                    modifier = coverModifier.background(Color.LightGray, RoundedCornerShape(6.dp)),
                )
            } else {
                FallbackCover(book, coverModifier)
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Serif,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val caption = book.seriesName?.let { series ->
            book.seriesIndex?.let { "$series · ${if (it % 1f == 0f) it.toInt() else it}" } ?: series
        } ?: book.author
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Books without embedded art get a quiet colored board with the title set in serif. */
@Composable
private fun FallbackCover(book: BookEntity, modifier: Modifier) {
    val palette = listOf(
        Color(0xFF5D5348), Color(0xFF4A5A5E), Color(0xFF5E4A55),
        Color(0xFF46584A), Color(0xFF57503F), Color(0xFF4C4A5E),
    )
    val background = palette[book.uuid.hashCode().absoluteValue % palette.size]
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = book.title,
            color = Color(0xFFF2EDE4),
            fontFamily = FontFamily.Serif,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
