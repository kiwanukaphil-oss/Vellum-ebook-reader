package app.vellum.reader.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookCollectionCrossRef
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.BookTagCrossRef
import app.vellum.reader.core.data.CollectionEntity
import app.vellum.reader.core.data.TagEntity
import app.vellum.reader.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class ShelfSort(val label: String) {
    RECENT("Recently read"),
    TITLE("Title"),
    AUTHOR("Author"),
    SERIES("Series"),
}

sealed interface ShelfFilter {
    data object All : ShelfFilter
    data class InCollection(val collectionUuid: String) : ShelfFilter
    data class WithTag(val tagUuid: String) : ShelfFilter
}

data class LibraryState(
    val books: List<BookEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    /** bookUuid → collection uuids / tag uuids the book belongs to. */
    val collectionsByBook: Map<String, Set<String>> = emptyMap(),
    val tagsByBook: Map<String, Set<String>> = emptyMap(),
    val sort: ShelfSort = ShelfSort.RECENT,
    val filter: ShelfFilter = ShelfFilter.All,
    val importing: Boolean = false,
)

class LibraryViewModel(private val app: VellumApp) : ViewModel() {

    private val importer = BookImporter(app)
    private val sort = MutableStateFlow(ShelfSort.RECENT)
    private val filter = MutableStateFlow<ShelfFilter>(ShelfFilter.All)
    private val importing = MutableStateFlow(false)

    /** Selection mode: non-empty = the shelf is in multi-select. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    val state: StateFlow<LibraryState> = combine(
        app.bookDao.observeShelf(),
        app.collectionDao.observeCollections(),
        app.collectionDao.observeTags(),
        app.collectionDao.observeBookCollections(),
        app.collectionDao.observeBookTags(),
        sort,
        filter,
        importing,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val books = values[0] as List<BookEntity>
        @Suppress("UNCHECKED_CAST")
        val collections = values[1] as List<CollectionEntity>
        @Suppress("UNCHECKED_CAST")
        val tags = values[2] as List<TagEntity>
        @Suppress("UNCHECKED_CAST")
        val bookCollections = values[3] as List<BookCollectionCrossRef>
        @Suppress("UNCHECKED_CAST")
        val bookTags = values[4] as List<BookTagCrossRef>
        val sortMode = values[5] as ShelfSort
        val filterMode = values[6] as ShelfFilter

        val collectionsByBook = bookCollections.groupBy({ it.bookUuid }, { it.collectionUuid })
            .mapValues { it.value.toSet() }
        val tagsByBook = bookTags.groupBy({ it.bookUuid }, { it.tagUuid })
            .mapValues { it.value.toSet() }

        val filtered = when (filterMode) {
            is ShelfFilter.All -> books
            is ShelfFilter.InCollection ->
                books.filter { filterMode.collectionUuid in collectionsByBook[it.uuid].orEmpty() }
            is ShelfFilter.WithTag ->
                books.filter { filterMode.tagUuid in tagsByBook[it.uuid].orEmpty() }
        }
        val sorted = when (sortMode) {
            ShelfSort.RECENT -> filtered // shelf query already orders by lastOpenedAt
            ShelfSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            ShelfSort.AUTHOR -> filtered.sortedBy { it.author.lowercase() }
            ShelfSort.SERIES -> filtered.sortedWith(
                compareBy({ it.seriesName ?: "￿" }, { it.seriesIndex ?: Float.MAX_VALUE }, { it.title }),
            )
        }
        LibraryState(
            books = sorted,
            collections = collections,
            tags = tags,
            collectionsByBook = collectionsByBook,
            tagsByBook = tagsByBook,
            sort = sortMode,
            filter = filterMode,
            importing = values[7] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    /** Human-readable line for the sync sheet ("Synced · 2 pulled" etc.). */
    val syncStatus = MutableStateFlow<String?>(null)
    val syncing = MutableStateFlow(false)

    init {
        viewModelScope.launch { importer.scanDropFolder() }
        // Auto-sync on open when a folder is configured.
        viewModelScope.launch {
            val settings = app.settingsStore.settings.first()
            settings.syncFolderUri?.let { syncNow(it) }
        }
    }

    /** Runs a full folder sync and repairs any assets pulled books need. */
    fun syncNow(folderUri: String) {
        if (syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            syncStatus.value = "Syncing…"
            try {
                val result = SyncEngine(app).sync(android.net.Uri.parse(folderUri))
                if (result.error != null) {
                    syncStatus.value = "Sync failed: ${result.error}"
                } else {
                    importer.ensureAssets()
                    app.settingsStore.setLastSyncAt(System.currentTimeMillis())
                    syncStatus.value = buildString {
                        append("Synced")
                        if (result.pulledBooks > 0) append(" · ${result.pulledBooks} pulled")
                        if (result.pushedBooks > 0) append(" · ${result.pushedBooks} pushed")
                    }
                }
            } finally {
                syncing.value = false
            }
        }
    }

    fun setSort(mode: ShelfSort) {
        sort.value = mode
    }

    fun toggleSelection(uuid: String) {
        _selected.value = _selected.value.let { if (uuid in it) it - uuid else it + uuid }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun deleteBooks(uuids: Set<String>) {
        state.value.books.filter { it.uuid in uuids }.forEach(::deleteBook)
        clearSelection()
    }

    /** Adds or removes every book in [bookUuids] from a collection at once. */
    fun setCollectionForBooks(collectionUuid: String, bookUuids: Set<String>, member: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            bookUuids.forEach { bookUuid ->
                app.collectionDao.upsertBookCollection(
                    BookCollectionCrossRef(bookUuid, collectionUuid, now, if (member) null else now),
                )
            }
        }
    }

    fun setTagForBooks(tagUuid: String, bookUuids: Set<String>, member: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            bookUuids.forEach { bookUuid ->
                app.collectionDao.upsertBookTag(
                    BookTagCrossRef(bookUuid, tagUuid, now, if (member) null else now),
                )
            }
        }
    }

    fun setFilter(newFilter: ShelfFilter) {
        filter.value = newFilter
    }

    fun importEpub(uri: Uri) {
        viewModelScope.launch {
            importing.value = true
            try {
                importer.importFromUri(uri)
            } finally {
                importing.value = false
            }
        }
    }

    fun updateMetadata(uuid: String, title: String, author: String, seriesName: String?, seriesIndex: Float?) {
        viewModelScope.launch {
            app.bookDao.updateMetadata(
                uuid,
                title.trim().ifBlank { "Untitled" },
                author.trim().ifBlank { "Unknown author" },
                seriesName?.trim()?.ifBlank { null },
                seriesIndex,
                System.currentTimeMillis(),
            )
        }
    }

    fun toggleCollection(bookUuid: String, collectionUuid: String, isMember: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            app.collectionDao.upsertBookCollection(
                BookCollectionCrossRef(bookUuid, collectionUuid, now, if (isMember) now else null),
            )
        }
    }

    fun createCollection(name: String, assignToBooks: Collection<String>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val collection = CollectionEntity(UUID.randomUUID().toString(), name.trim(), now, now, null)
            app.collectionDao.upsertCollection(collection)
            assignToBooks.forEach {
                app.collectionDao.upsertBookCollection(BookCollectionCrossRef(it, collection.uuid, now, null))
            }
        }
    }

    fun toggleTag(bookUuid: String, tagUuid: String, isMember: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            app.collectionDao.upsertBookTag(BookTagCrossRef(bookUuid, tagUuid, now, if (isMember) now else null))
        }
    }

    fun createTag(name: String, assignToBooks: Collection<String>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val tag = TagEntity(UUID.randomUUID().toString(), name.trim(), now, now, null)
            app.collectionDao.upsertTag(tag)
            assignToBooks.forEach {
                app.collectionDao.upsertBookTag(BookTagCrossRef(it, tag.uuid, now, null))
            }
        }
    }

    /**
     * Tombstones the row (sync-ready) and removes everything local: file,
     * cover, search index, reading position, annotations, and PDF ink.
     * Never touches the original file the book was imported from.
     */
    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            app.bookDao.softDelete(book.uuid, now)
            app.bookDao.deletePosition(book.uuid)
            app.annotationDao.softDeleteForBook(book.uuid, now)
            app.pdfStrokeDao.softDeleteForBook(book.uuid, now)
            app.comicPanelDao.softDeleteForBook(book.uuid, now)
            app.searchDao.deleteForBook(book.uuid)
            File(app.booksDir, book.fileName).delete()
            book.coverPath?.let { File(it).delete() }
        }
    }
}
