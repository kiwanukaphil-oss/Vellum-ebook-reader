package app.vellum.reader.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.settings.ReaderSettings
import app.vellum.reader.core.theme.Fraunces
import app.vellum.reader.core.theme.PlexMono
import app.vellum.reader.core.theme.sharedCoverBounds
import app.vellum.reader.nearby.AddBooksSheet
import app.vellum.reader.nearby.NearbyTransferMode
import app.vellum.reader.nearby.NearbyTransferSheet
import app.vellum.reader.shared.SharedAccountState
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenBook: (BookEntity) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSharedLibraries: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as VellumApp
    val viewModel: LibraryViewModel = viewModel { LibraryViewModel(app) }
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val selectionMode = selected.isNotEmpty()
    var activeTab by rememberSaveable { mutableStateOf(LibraryTab.BROWSE) }
    var libraryMenuOpen by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<BookEntity?>(null) }
    var organizeOpen by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var syncSheetOpen by remember { mutableStateOf(false) }
    var addBooksOpen by rememberSaveable { mutableStateOf(false) }
    var aiLibrarianOpen by rememberSaveable { mutableStateOf(false) }
    var nearbyMode by rememberSaveable { mutableStateOf<NearbyTransferMode?>(null) }
    val settings by app.settingsStore.settings.collectAsState(initial = ReaderSettings())
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val aiProcessing by viewModel.aiProcessing.collectAsState()
    val aiAccount by viewModel.aiAccount.collectAsState()
    val aiStatus by viewModel.aiStatus.collectAsState()
    val scope = rememberCoroutineScope()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importBooks(uris)
    }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }
    LaunchedEffect(Unit) { app.importNotices.collect { snackbarHostState.showSnackbar(it) } }

    fun openCategory(category: String) {
        viewModel.showCategory(category)
        activeTab = LibraryTab.ALL_BOOKS
    }
    fun openGenre(uuid: String) {
        viewModel.showGenre(uuid)
        activeTab = LibraryTab.ALL_BOOKS
    }
    fun manage(book: BookEntity) { detailsFor = book }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "libraryTopBar",
            ) { inSelection ->
                if (inSelection) {
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
                                    detailsFor = state.allBooks.firstOrNull { it.uuid in selected }
                                }) { Icon(Icons.Filled.Edit, contentDescription = "Edit book") }
                            }
                            IconButton(onClick = {
                                viewModel.organizeBooks(selected)
                                aiLibrarianOpen = true
                            }) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = "Organise selected books automatically")
                            }
                            IconButton(onClick = { organizeOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Organize selected books")
                            }
                            IconButton(onClick = { nearbyMode = NearbyTransferMode.SEND }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share selected books nearby")
                            }
                            IconButton(onClick = { confirmBatchDelete = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove selected")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                } else {
                    TopAppBar(
                        title = {
                            Box {
                                TextButton(onClick = { sourceMenuOpen = true }) {
                                    Text(
                                        "My Library",
                                        fontFamily = Fraunces,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Icon(Icons.Filled.ExpandMore, contentDescription = "Choose library source")
                                }
                                DropdownMenu(
                                    expanded = sourceMenuOpen,
                                    onDismissRequest = { sourceMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("My Library")
                                                Text(
                                                    "Private and available offline",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                                        onClick = { sourceMenuOpen = false },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Shared Libraries")
                                                Text(
                                                    "Private household catalogues",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                                        onClick = {
                                            sourceMenuOpen = false
                                            onOpenSharedLibraries()
                                        },
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = onOpenSearch) {
                                Icon(Icons.Filled.Search, contentDescription = "Search library")
                            }
                            IconButton(onClick = { syncSheetOpen = true }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Sync")
                            }
                            Box {
                                IconButton(onClick = { libraryMenuOpen = true }) {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Library view options")
                                }
                                DropdownMenu(
                                    expanded = libraryMenuOpen,
                                    onDismissRequest = { libraryMenuOpen = false },
                                ) {
                                    Text(
                                        "GROUP ALL BOOKS BY",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                    LibraryGroup.entries.forEach { group ->
                                        DropdownMenuItem(
                                            text = { Text(if (group == state.groupBy) "✓ ${group.label}" else group.label) },
                                            onClick = {
                                                viewModel.setGroupBy(group)
                                                activeTab = LibraryTab.ALL_BOOKS
                                                libraryMenuOpen = false
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    Text(
                                        "SORT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                    ShelfSort.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(if (mode == state.sort) "✓ ${mode.label}" else mode.label) },
                                            onClick = {
                                                viewModel.setSort(mode)
                                                libraryMenuOpen = false
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("AI Librarian")
                                                Text(
                                                    "Name and organise books automatically",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                                        onClick = {
                                            libraryMenuOpen = false
                                            aiLibrarianOpen = true
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !selectionMode,
                enter = scaleIn(tween(180)) + fadeIn(tween(180)),
                exit = scaleOut(tween(140)) + fadeOut(tween(140)),
            ) {
                FloatingActionButton(
                    onClick = { addBooksOpen = true },
                ) { Icon(Icons.Filled.Add, contentDescription = "Add one or more books") }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (!selectionMode) {
                LibraryTabs(activeTab = activeTab, onTab = { activeTab = it })
            }
            when (activeTab) {
                LibraryTab.BROWSE -> BrowseLibrary(
                    state = state,
                    selected = selected,
                    selectionMode = selectionMode,
                    onOpenBook = onOpenBook,
                    onToggleSelection = viewModel::toggleSelection,
                    onManage = ::manage,
                    onOpenCategory = ::openCategory,
                    onOpenGenre = ::openGenre,
                    onNeedsCategory = {
                        viewModel.showNeedsCategory()
                        activeTab = LibraryTab.ALL_BOOKS
                    },
                    onShowAll = {
                        viewModel.clearLibraryFilters()
                        activeTab = LibraryTab.ALL_BOOKS
                    },
                )
                LibraryTab.ALL_BOOKS -> AllBooksLibrary(
                    state = state,
                    selected = selected,
                    selectionMode = selectionMode,
                    onOpenBook = onOpenBook,
                    onToggleSelection = viewModel::toggleSelection,
                    onManage = ::manage,
                    onClearFilters = viewModel::clearLibraryFilters,
                )
                LibraryTab.COLLECTIONS -> CollectionsLibrary(
                    state = state,
                    onOpenCollection = { collectionUuid ->
                        viewModel.clearLibraryFilters()
                        viewModel.setFilter(ShelfFilter.InCollection(collectionUuid))
                        activeTab = LibraryTab.ALL_BOOKS
                    },
                    onShowAll = {
                        viewModel.clearLibraryFilters()
                        activeTab = LibraryTab.ALL_BOOKS
                    },
                )
            }
        }
    }

    detailsFor?.let { book ->
        BookDetailsSheet(book, state, viewModel, onDismiss = { detailsFor = null })
    }
    if (organizeOpen) {
        OrganizeSheet(state, selected, viewModel, onDismiss = { organizeOpen = false })
    }
    if (syncSheetOpen) {
        SyncSheet(
            folderConfigured = settings.syncFolderUri != null,
            lastSyncAt = settings.lastSyncAt,
            status = syncStatus,
            syncing = syncing,
            onChooseFolder = { folderPicker.launch(null) },
            onSyncNow = { settings.syncFolderUri?.let(viewModel::syncNow) },
            onDismiss = {
                syncSheetOpen = false
                viewModel.clearSyncStatus()
            },
        )
    }
    if (addBooksOpen) {
        AddBooksSheet(
            onChooseFiles = {
                addBooksOpen = false
                picker.launch(
                    arrayOf(
                        "application/epub+zip", "application/pdf", "application/zip",
                        "application/x-rar-compressed", "application/vnd.rar", "application/rar",
                        "application/x-cbz", "application/x-cbr",
                        "application/vnd.comicbook+zip", "application/vnd.comicbook-rar",
                        "application/octet-stream",
                    ),
                )
            },
            onReceiveNearby = {
                addBooksOpen = false
                nearbyMode = NearbyTransferMode.RECEIVE
            },
            onDismiss = { addBooksOpen = false },
        )
    }
    if (aiLibrarianOpen) {
        AiLibrarianSheet(
            books = state.allBooks,
            suggestions = aiSuggestions,
            processing = aiProcessing,
            signedIn = aiAccount is SharedAccountState.SignedIn,
            status = aiStatus,
            onOrganize = viewModel::organizeLibrary,
            onApply = viewModel::applyAiSuggestion,
            onDismissSuggestion = viewModel::dismissAiSuggestion,
            onUndo = viewModel::undoAiSuggestion,
            onSignIn = {
                aiLibrarianOpen = false
                onOpenSharedLibraries()
            },
            onDismiss = {
                aiLibrarianOpen = false
                viewModel.clearAiStatus()
            },
        )
    }
    nearbyMode?.let { mode ->
        NearbyTransferSheet(
            mode = mode,
            manager = app.nearbyTransferManager,
            books = if (mode == NearbyTransferMode.SEND) state.allBooks.filter { it.uuid in selected } else emptyList(),
            onDismiss = { nearbyMode = null },
        )
    }
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("Remove ${if (selected.size == 1) "this book" else "${selected.size} books"}?") },
            text = { Text("Highlights, notes, and reading progress will be deleted. Original files on your device aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBooks(selected)
                    confirmBatchDelete = false
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBatchDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LibraryTabs(activeTab: LibraryTab, onTab: (LibraryTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        listOf(
            LibraryTab.BROWSE to "Browse",
            LibraryTab.ALL_BOOKS to "All books",
            LibraryTab.COLLECTIONS to "Collections",
        ).forEach { (tab, label) ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(
                    onClick = { onTab(tab) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (activeTab == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (activeTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Box(
                    Modifier
                        .width(if (activeTab == tab) 36.dp else 0.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseLibrary(
    state: LibraryState,
    selected: Set<String>,
    selectionMode: Boolean,
    onOpenBook: (BookEntity) -> Unit,
    onToggleSelection: (String) -> Unit,
    onManage: (BookEntity) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onNeedsCategory: () -> Unit,
    onShowAll: () -> Unit,
) {
    if (state.allBooks.isEmpty() && !state.importing) {
        EmptyLibrary()
        return
    }
    val continueBook = state.allBooks.filter { it.lastOpenedAt != null }.maxByOrNull { it.lastOpenedAt ?: 0L }
    val popularGenres = state.genres.map { genre ->
        genre to state.allBooks.count { genre.uuid in state.genresByBook[it.uuid].orEmpty() }
    }.filter { it.second > 0 }.sortedByDescending { it.second }.take(8)
    val recent = state.allBooks.sortedByDescending { it.addedAt }.take(12)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val categoryColumns = if (maxWidth >= 700.dp) 4 else 2
    LazyColumn(
        contentPadding = PaddingValues(bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.widthIn(max = 1200.dp).fillMaxSize().align(Alignment.TopCenter),
    ) {
        item {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                Text("Your library", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Find a book by what it is, or by the shelf you made for it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        continueBook?.let { book ->
            item {
                Column {
                    SectionHeading("CONTINUE READING")
                    ContinueCard(book = book, onClick = { onOpenBook(book) })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading("BROWSE BY CATEGORY")
                BookCategories.all.chunked(categoryColumns).forEach { rowCategories ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowCategories.forEach { category ->
                            val books = state.allBooks.filter { it.category == category }
                            CategoryCard(
                                category = category,
                                books = books,
                                onClick = { onOpenCategory(category) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(categoryColumns - rowCategories.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (state.uncategorizedCount > 0) {
                    Surface(
                        onClick = onNeedsCategory,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Needs category", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${state.uncategorizedCount} ${if (state.uncategorizedCount == 1) "book is" else "books are"} waiting to be placed",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text("Organize", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        if (popularGenres.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading("POPULAR GENRES")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(popularGenres, key = { it.first.uuid }) { (genre, count) ->
                            FilterChip(
                                selected = false,
                                onClick = { onOpenGenre(genre.uuid) },
                                label = { Text("${genre.name}  $count") },
                            )
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("RECENTLY ADDED", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onShowAll) { Text("See all") }
                    }
                    BookStrip(
                        books = recent,
                        selected = selected,
                        selectionMode = selectionMode,
                        onOpenBook = onOpenBook,
                        onToggleSelection = onToggleSelection,
                        onManage = onManage,
                    )
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllBooksLibrary(
    state: LibraryState,
    selected: Set<String>,
    selectionMode: Boolean,
    onOpenBook: (BookEntity) -> Unit,
    onToggleSelection: (String) -> Unit,
    onManage: (BookEntity) -> Unit,
    onClearFilters: () -> Unit,
) {
    val groups = remember(state.books, state.groupBy, state.genresByBook, state.genres) {
        groupBooks(state)
    }
    val hasFilter = state.filter != ShelfFilter.All || state.categoryFilter != null ||
        state.genreFilterUuid != null || state.needsCategoryOnly
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val minimumCoverWidth = if (maxWidth >= 600.dp) 132.dp else 105.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minimumCoverWidth),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.widthIn(max = 1400.dp).fillMaxSize().align(Alignment.TopCenter),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("All books", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "${state.books.size} ${if (state.books.size == 1) "book" else "books"} · grouped by ${state.groupBy.label.lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hasFilter) TextButton(onClick = onClearFilters) { Text("Clear filter") }
                }
                ActiveFilterLabel(state)
            }
        }
        if (state.books.isEmpty() && !state.importing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (hasFilter) "No books match this shelf." else "Your library is empty.")
                    if (hasFilter) TextButton(onClick = onClearFilters) { Text("Show all books") }
                }
            }
        } else {
            groups.forEach { (heading, books) ->
                if (heading.isNotBlank()) {
                    item(key = "heading-$heading", span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(heading.uppercase(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${books.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(books, key = { "$heading-${it.uuid}" }) { book ->
                    BookCard(
                        book = book,
                        isSelected = book.uuid in selected,
                        onClick = {
                            if (selectionMode) onToggleSelection(book.uuid) else onOpenBook(book)
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleSelection(book.uuid)
                        },
                        onManage = { onManage(book) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun CollectionsLibrary(
    state: LibraryState,
    onOpenCollection: (String) -> Unit,
    onShowAll: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val columns = if (maxWidth >= 700.dp) 2 else 1
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize().align(Alignment.TopCenter),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text("Collections", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Personal shelves for moods, projects, and reading plans.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            CollectionCard(
                name = "Complete library",
                books = state.allBooks,
                supporting = "Every book, in one place",
                onClick = onShowAll,
            )
        }
        items(state.collections, key = { it.uuid }) { collection ->
            val books = state.allBooks.filter { collection.uuid in state.collectionsByBook[it.uuid].orEmpty() }
            CollectionCard(
                name = collection.name,
                books = books,
                supporting = "${books.size} ${if (books.size == 1) "book" else "books"}",
                onClick = { onOpenCollection(collection.uuid) },
            )
        }
        if (state.collections.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Select one or more books and choose Organize to make your first personal collection.",
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun ActiveFilterLabel(state: LibraryState) {
    val label = when {
        state.needsCategoryOnly -> "Needs category"
        state.categoryFilter != null -> state.categoryFilter
        state.genreFilterUuid != null -> state.genresById[state.genreFilterUuid]?.name
        state.filter is ShelfFilter.InCollection -> state.collections.firstOrNull {
            it.uuid == state.filter.collectionUuid
        }?.name
        state.filter is ShelfFilter.WithTag -> state.tags.firstOrNull {
            it.uuid == state.filter.tagUuid
        }?.let { "#${it.name}" }
        else -> null
    }
    if (label != null) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun groupBooks(state: LibraryState): List<Pair<String, List<BookEntity>>> = when (state.groupBy) {
    LibraryGroup.CATEGORY -> state.books.groupBy { it.category ?: "Needs category" }.toSortedMap(
        compareBy { heading ->
            val index = BookCategories.all.indexOf(heading)
            if (index >= 0) index else Int.MAX_VALUE
        },
    ).toList()
    LibraryGroup.GENRE -> state.genres.mapNotNull { genre ->
        val books = state.books.filter { genre.uuid in state.genresByBook[it.uuid].orEmpty() }
        books.takeIf { it.isNotEmpty() }?.let { genre.name to it }
    } + state.books.filter { state.genresByBook[it.uuid].orEmpty().isEmpty() }.let { books ->
        if (books.isEmpty()) emptyList() else listOf("No genre" to books)
    }
    LibraryGroup.SERIES -> state.books.groupBy { it.seriesName ?: "Standalone" }.toSortedMap().toList()
    LibraryGroup.AUTHOR -> state.books.groupBy { it.author }.toSortedMap(String.CASE_INSENSITIVE_ORDER).toList()
    LibraryGroup.NONE -> listOf("" to state.books)
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

@Composable
private fun ContinueCard(book: BookEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.height(132.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(book, Modifier.width(72.dp).fillMaxHeight())
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("CONTINUE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: String,
    books: List<BookEntity>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.height(142.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            MiniCoverStack(books.take(3), Modifier.height(66.dp))
            Spacer(Modifier.weight(1f))
            Text(category, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${books.size} ${if (books.size == 1) "book" else "books"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CollectionCard(
    name: String,
    books: List<BookEntity>,
    supporting: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().height(126.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniCoverStack(books.take(3), Modifier.width(112.dp).fillMaxHeight())
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MiniCoverStack(books: List<BookEntity>, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (books.isEmpty()) {
            Box(
                Modifier
                    .width(44.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        } else {
            books.forEachIndexed { index, book ->
                CoverImage(
                    book,
                    Modifier
                        .padding(start = (index * 24).dp)
                        .width(44.dp)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookStrip(
    books: List<BookEntity>,
    selected: Set<String>,
    selectionMode: Boolean,
    onOpenBook: (BookEntity) -> Unit,
    onToggleSelection: (String) -> Unit,
    onManage: (BookEntity) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(books, key = { it.uuid }) { book ->
            BookCard(
                book = book,
                isSelected = book.uuid in selected,
                onClick = { if (selectionMode) onToggleSelection(book.uuid) else onOpenBook(book) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleSelection(book.uuid)
                },
                onManage = { onManage(book) },
                modifier = Modifier.width(108.dp),
            )
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("A quiet shelf, for now", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tap + to add an EPUB, PDF, or comic.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoverImage(book: BookEntity, modifier: Modifier) {
    val shape = RoundedCornerShape(topStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 2.dp)
    val coverFile = remember(book.coverPath) { book.coverPath?.let(::File)?.takeIf(File::exists) }
    if (coverFile != null) {
        AsyncImage(
            model = coverFile,
            contentDescription = "Cover of ${book.title}",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    } else {
        FallbackCover(book, modifier.clip(shape))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            val spineShape = RoundedCornerShape(topStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 2.dp)
            val coverModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .sharedCoverBounds(book.uuid)
                .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, spineShape) else Modifier)
            CoverImage(book, coverModifier)
            if (isSelected) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            } else {
                IconButton(onClick = onManage, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                    Box(
                        modifier = Modifier.size(30.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MoreVert, "Manage ${book.title}", modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = Fraunces,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val caption = book.seriesName?.let { series ->
            book.seriesIndex?.let { "$series · ${if (it % 1f == 0f) it.toInt() else it}" } ?: series
        } ?: if (book.author == "Unknown author") book.format.displayFormat() else book.author
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = PlexMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun String.displayFormat(): String = when (this) {
    "pdf" -> "PDF"
    "cbz", "cbr", "comic-epub" -> "Comic"
    else -> uppercase()
}

@Composable
private fun FallbackCover(book: BookEntity, modifier: Modifier) {
    val palette = listOf(
        Color(0xFF5D5348), Color(0xFF4A5A5E), Color(0xFF5E4A55),
        Color(0xFF46584A), Color(0xFF57503F), Color(0xFF4C4A5E),
    )
    val background = palette[book.uuid.hashCode().absoluteValue % palette.size]
    Box(
        modifier = modifier.background(background).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            book.title,
            color = Color(0xFFF2EDE4),
            fontFamily = Fraunces,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
