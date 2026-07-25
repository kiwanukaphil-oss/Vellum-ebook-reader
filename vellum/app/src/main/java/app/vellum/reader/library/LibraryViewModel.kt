package app.vellum.reader.library

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookCollectionCrossRef
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.BookGenreCrossRef
import app.vellum.reader.core.data.BookTagCrossRef
import app.vellum.reader.core.data.CollectionEntity
import app.vellum.reader.core.data.GenreEntity
import app.vellum.reader.core.data.TagEntity
import app.vellum.reader.librarian.AiOrganizeOutcome
import app.vellum.reader.librarian.VellumAiTaxonomy
import app.vellum.reader.shared.SharedAccountState
import app.vellum.reader.sync.SyncEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ShelfSort(val label: String) {
    RECENT("Recently read"),
    TITLE("Title"),
    AUTHOR("Author"),
    SERIES("Series"),
}

enum class LibraryGroup(val label: String, val description: String) {
    CATEGORY("Category", "Fiction, Non-fiction, Comics…"),
    GENRE("Genre", "Romance, History, Science…"),
    SERIES("Series", "Keep volumes together"),
    AUTHOR("Author", "Alphabetical by surname"),
    NONE("None", "One uninterrupted grid"),
}

enum class LibraryTab { BROWSE, ALL_BOOKS, COLLECTIONS }

object BookCategories {
    const val FICTION = "Fiction"
    const val NON_FICTION = "Non-fiction"
    const val COMICS = "Comics & Manga"
    const val ESSAYS = "Essays & Poetry"

    val all = listOf(FICTION, NON_FICTION, COMICS, ESSAYS)
}

sealed interface ShelfFilter {
    data object All : ShelfFilter
    data class InCollection(val collectionUuid: String) : ShelfFilter
    data class WithTag(val tagUuid: String) : ShelfFilter
}

data class LibraryState(
    /** Every live book, used by Browse counts and collections. */
    val allBooks: List<BookEntity> = emptyList(),
    /** Current filtered and sorted All-books result. */
    val books: List<BookEntity> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val genres: List<GenreEntity> = emptyList(),
    val collectionsByBook: Map<String, Set<String>> = emptyMap(),
    val tagsByBook: Map<String, Set<String>> = emptyMap(),
    val genresByBook: Map<String, Set<String>> = emptyMap(),
    val sort: ShelfSort = ShelfSort.RECENT,
    val filter: ShelfFilter = ShelfFilter.All,
    val categoryFilter: String? = null,
    val genreFilterUuid: String? = null,
    val needsCategoryOnly: Boolean = false,
    val groupBy: LibraryGroup = LibraryGroup.CATEGORY,
    val importing: Boolean = false,
) {
    val genresById: Map<String, GenreEntity> get() = genres.associateBy { it.uuid }
    val uncategorizedCount: Int get() = allBooks.count { it.category == null }
}

class LibraryViewModel(private val app: VellumApp) : ViewModel() {
    private val importer = BookImporter(app)
    private val sort = MutableStateFlow(ShelfSort.RECENT)
    private val filter = MutableStateFlow<ShelfFilter>(ShelfFilter.All)
    private val categoryFilter = MutableStateFlow<String?>(null)
    private val genreFilterUuid = MutableStateFlow<String?>(null)
    private val needsCategoryOnly = MutableStateFlow(false)
    private val groupBy = MutableStateFlow(LibraryGroup.CATEGORY)
    private val importing = MutableStateFlow(false)

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    val state: StateFlow<LibraryState> = combine(
        app.bookDao.observeShelf(),
        app.collectionDao.observeCollections(),
        app.collectionDao.observeTags(),
        app.collectionDao.observeBookCollections(),
        app.collectionDao.observeBookTags(),
        app.collectionDao.observeGenres(),
        app.collectionDao.observeBookGenres(),
        sort,
        filter,
        categoryFilter,
        genreFilterUuid,
        needsCategoryOnly,
        groupBy,
        importing,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val allBooks = values[0] as List<BookEntity>
        @Suppress("UNCHECKED_CAST")
        val collections = values[1] as List<CollectionEntity>
        @Suppress("UNCHECKED_CAST")
        val tags = values[2] as List<TagEntity>
        @Suppress("UNCHECKED_CAST")
        val bookCollections = values[3] as List<BookCollectionCrossRef>
        @Suppress("UNCHECKED_CAST")
        val bookTags = values[4] as List<BookTagCrossRef>
        @Suppress("UNCHECKED_CAST")
        val genres = values[5] as List<GenreEntity>
        @Suppress("UNCHECKED_CAST")
        val bookGenres = values[6] as List<BookGenreCrossRef>
        val sortMode = values[7] as ShelfSort
        val legacyFilter = values[8] as ShelfFilter
        val category = values[9] as String?
        val genreUuid = values[10] as String?
        val needsCategory = values[11] as Boolean
        val grouping = values[12] as LibraryGroup

        val collectionsByBook = bookCollections.groupBy({ it.bookUuid }, { it.collectionUuid })
            .mapValues { it.value.toSet() }
        val tagsByBook = bookTags.groupBy({ it.bookUuid }, { it.tagUuid })
            .mapValues { it.value.toSet() }
        val genresByBook = bookGenres.groupBy({ it.bookUuid }, { it.genreUuid })
            .mapValues { it.value.toSet() }

        val filtered = allBooks.filter { book ->
            val legacyMatch = when (legacyFilter) {
                ShelfFilter.All -> true
                is ShelfFilter.InCollection -> legacyFilter.collectionUuid in collectionsByBook[book.uuid].orEmpty()
                is ShelfFilter.WithTag -> legacyFilter.tagUuid in tagsByBook[book.uuid].orEmpty()
            }
            legacyMatch &&
                (!needsCategory || book.category == null) &&
                (category == null || book.category == category) &&
                (genreUuid == null || genreUuid in genresByBook[book.uuid].orEmpty())
        }
        val sorted = when (sortMode) {
            ShelfSort.RECENT -> filtered
            ShelfSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            ShelfSort.AUTHOR -> filtered.sortedBy { it.author.lowercase() }
            ShelfSort.SERIES -> filtered.sortedWith(
                compareBy({ it.seriesName ?: "\uFFFF" }, { it.seriesIndex ?: Float.MAX_VALUE }, { it.title }),
            )
        }

        LibraryState(
            allBooks = allBooks,
            books = sorted,
            collections = collections,
            tags = tags,
            genres = genres,
            collectionsByBook = collectionsByBook,
            tagsByBook = tagsByBook,
            genresByBook = genresByBook,
            sort = sortMode,
            filter = legacyFilter,
            categoryFilter = category,
            genreFilterUuid = genreUuid,
            needsCategoryOnly = needsCategory,
            groupBy = grouping,
            importing = values[13] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    val syncStatus = MutableStateFlow<String?>(null)
    val syncing = MutableStateFlow(false)
    val aiSuggestions = app.aiLibrarian.suggestions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val aiProcessing = app.aiLibrarian.processing
    val aiAccount = app.sharedLibraryRepository.accountState
    val aiStatus = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            app.bookDao.observeShelf().collect { books ->
                _selected.value = _selected.value.intersect(books.mapTo(mutableSetOf()) { it.uuid })
            }
        }
        viewModelScope.launch { ensureDefaultGenres() }
        viewModelScope.launch {
            delay(600)
            importer.scanDropFolder()
            app.settingsStore.settings.first().syncFolderUri?.let(::syncNow)
        }
    }

    private suspend fun ensureDefaultGenres() {
        val existing = app.collectionDao.allGenresRaw()
            .filter { it.deletedAt == null }
            .map { it.name.lowercase() }
            .toSet()
        val now = System.currentTimeMillis()
        VellumAiTaxonomy.genres.filter { it.lowercase() !in existing }.forEach { name ->
            app.collectionDao.upsertGenre(
                GenreEntity(
                    uuid = UUID.nameUUIDFromBytes("vellum-genre:$name".toByteArray()).toString(),
                    name = name,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }
    }

    fun syncNow(folderUri: String) {
        if (syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            syncStatus.value = "Syncing…"
            try {
                val result = SyncEngine(app).sync(Uri.parse(folderUri))
                if (result.error != null) {
                    Log.e("VellumSync", "Sync failed: ${result.error}")
                    syncStatus.value = "Couldn't sync — check that the folder is still accessible."
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

    fun clearSyncStatus() { if (!syncing.value) syncStatus.value = null }
    fun clearAiStatus() { aiStatus.value = null }
    fun setSort(mode: ShelfSort) { sort.value = mode }
    fun setGroupBy(group: LibraryGroup) { groupBy.value = group }

    fun showCategory(category: String?) {
        categoryFilter.value = category
        genreFilterUuid.value = null
        needsCategoryOnly.value = false
        filter.value = ShelfFilter.All
        groupBy.value = if (category == null) LibraryGroup.CATEGORY else LibraryGroup.GENRE
    }

    fun showGenre(uuid: String?) {
        categoryFilter.value = null
        genreFilterUuid.value = uuid
        needsCategoryOnly.value = false
        filter.value = ShelfFilter.All
        if (uuid != null) groupBy.value = LibraryGroup.CATEGORY
    }

    fun showNeedsCategory() {
        categoryFilter.value = null
        genreFilterUuid.value = null
        needsCategoryOnly.value = true
        filter.value = ShelfFilter.All
        groupBy.value = LibraryGroup.NONE
    }

    fun clearLibraryFilters() {
        categoryFilter.value = null
        genreFilterUuid.value = null
        needsCategoryOnly.value = false
        filter.value = ShelfFilter.All
    }

    fun toggleSelection(uuid: String) {
        _selected.value = _selected.value.let { if (uuid in it) it - uuid else it + uuid }
    }
    fun clearSelection() { _selected.value = emptySet() }

    fun deleteBooks(uuids: Set<String>) {
        clearSelection()
        viewModelScope.launch { uuids.mapNotNull { app.bookDao.byUuid(it) }.forEach { deleteBookNow(it) } }
    }

    fun setCategoryForBooks(category: String?, bookUuids: Set<String>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            app.database.withTransaction {
                bookUuids.forEach { app.bookDao.updateCategory(it, category, now) }
            }
        }
    }

    fun setGenreForBooks(genreUuid: String, bookUuids: Set<String>, member: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            app.database.withTransaction {
                bookUuids.forEach { bookUuid ->
                    app.collectionDao.upsertBookGenre(
                        BookGenreCrossRef(bookUuid, genreUuid, now, if (member) null else now),
                    )
                }
            }
        }
    }

    fun updateClassification(bookUuid: String, category: String?, genreUuids: Set<String>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val knownGenreUuids = state.value.genres.mapTo(mutableSetOf()) { it.uuid }
            knownGenreUuids += state.value.genresByBook[bookUuid].orEmpty()
            app.database.withTransaction {
                app.bookDao.updateCategory(bookUuid, category, now)
                knownGenreUuids.forEach { genreUuid ->
                    app.collectionDao.upsertBookGenre(
                        BookGenreCrossRef(
                            bookUuid = bookUuid,
                            genreUuid = genreUuid,
                            updatedAt = now,
                            deletedAt = if (genreUuid in genreUuids) null else now,
                        ),
                    )
                }
            }
        }
    }

    fun createGenre(name: String, assignToBooks: Collection<String>) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            val existing = state.value.genres.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
            val now = System.currentTimeMillis()
            val genre = existing ?: GenreEntity(UUID.randomUUID().toString(), cleanName, now, now, null).also {
                app.collectionDao.upsertGenre(it)
            }
            assignToBooks.forEach {
                app.collectionDao.upsertBookGenre(BookGenreCrossRef(it, genre.uuid, now, null))
            }
        }
    }

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
                app.collectionDao.upsertBookTag(BookTagCrossRef(bookUuid, tagUuid, now, if (member) null else now))
            }
        }
    }

    fun setFilter(newFilter: ShelfFilter) { filter.value = newFilter }

    fun importEpub(uri: Uri) = importBooks(listOf(uri))

    /** Imports a picker selection in order, keeping one reliable progress state. */
    fun importBooks(uris: List<Uri>) {
        val uniqueUris = uris.distinct()
        if (uniqueUris.isEmpty()) return
        viewModelScope.launch {
            importing.value = true
            try {
                uniqueUris.forEach { uri ->
                    try {
                        importer.importFromUri(uri)
                    } catch (exception: Exception) {
                        Log.e("VellumImport", "Picker import failed for $uri", exception)
                        app.importNotices.tryEmit("Couldn't import one of the selected files")
                    }
                }
            } finally {
                importing.value = false
            }
        }
    }

    fun organizeLibrary(force: Boolean = false) {
        if (aiProcessing.value.isNotEmpty()) return
        viewModelScope.launch {
            if (aiAccount.value !is SharedAccountState.SignedIn) {
                aiStatus.value = "Sign in to Shared Libraries once to use the private AI Librarian."
                return@launch
            }
            val candidates = if (force) {
                state.value.allBooks
            } else {
                state.value.allBooks.filter { book ->
                    book.category == null ||
                        book.author.equals("Unknown author", ignoreCase = true) ||
                        '_' in book.title ||
                        state.value.genresByBook[book.uuid].isNullOrEmpty()
                }
            }
            if (state.value.allBooks.isEmpty()) {
                aiStatus.value = "Add a few books and the librarian will begin shaping your shelves."
                return@launch
            }
            aiStatus.value = if (candidates.isEmpty()) {
                "Curating collections…"
            } else {
                "Organising 0 of ${candidates.size}…"
            }
            val summary = app.aiLibrarian.organizeAll(
                bookUuids = candidates.map { it.uuid },
                force = force,
            ) { complete, total ->
                aiStatus.value = "Organising $complete of $total…"
            }
            aiStatus.value = buildString {
                append("${summary.appliedBooks} organised")
                if (summary.reviewBooks > 0) {
                    append(" · ${summary.reviewBooks} ")
                    append(if (summary.reviewBooks == 1) "suggestion" else "suggestions")
                    append(" to review")
                }
                if (summary.curatedCollections > 0) {
                    append(" · ${summary.curatedCollections} ")
                    append(if (summary.curatedCollections == 1) "collection shaped" else "collections shaped")
                }
                if (
                    summary.appliedBooks == 0 &&
                    summary.reviewBooks == 0 &&
                    summary.curatedCollections == 0
                ) {
                    append(" · everything is already in place")
                }
                if (!summary.thematicCurationAvailable) {
                    append(" · author and series shelves updated; themes can be retried later")
                }
            }
        }
    }

    fun organizeBooks(bookUuids: Set<String>, force: Boolean = true) {
        if (bookUuids.isEmpty() || aiProcessing.value.isNotEmpty()) return
        viewModelScope.launch {
            if (aiAccount.value !is SharedAccountState.SignedIn) {
                aiStatus.value = "Sign in to Shared Libraries once to use the private AI Librarian."
                return@launch
            }
            val summary = app.aiLibrarian.organizeAll(bookUuids.toList(), force)
            aiStatus.value = buildString {
                append("${summary.appliedBooks} organised")
                if (summary.reviewBooks > 0) append(" · ${summary.reviewBooks} to review")
                if (summary.curatedCollections > 0) append(" · ${summary.curatedCollections} collections shaped")
            }
        }
    }

    fun applyAiSuggestion(uuid: String) {
        viewModelScope.launch {
            aiStatus.value = if (app.aiLibrarian.apply(uuid)) "Suggestion applied · You can undo it below." else null
        }
    }

    fun dismissAiSuggestion(uuid: String) {
        viewModelScope.launch { app.aiLibrarian.dismiss(uuid) }
    }

    fun undoAiSuggestion(uuid: String) {
        viewModelScope.launch {
            aiStatus.value = if (app.aiLibrarian.undo(uuid)) {
                "Organisation undone."
            } else {
                "This book was edited afterwards, so Vellum left your newer changes untouched."
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
        setCollectionForBooks(collectionUuid, setOf(bookUuid), !isMember)
    }

    fun createCollection(name: String, assignToBooks: Collection<String>) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val collection = CollectionEntity(
                uuid = UUID.randomUUID().toString(),
                name = cleanName,
                kind = "manual",
                description = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
            app.collectionDao.upsertCollection(collection)
            assignToBooks.forEach {
                app.collectionDao.upsertBookCollection(BookCollectionCrossRef(it, collection.uuid, now, null))
            }
        }
    }

    fun saveCollection(
        collectionUuid: String,
        name: String,
        kind: String,
        description: String?,
        bookUuids: Set<String>,
        onFinished: () -> Unit = {},
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            app.database.withTransaction {
                val existing = app.collectionDao.allCollectionsRaw()
                    .firstOrNull { it.uuid == collectionUuid && it.deletedAt == null }
                    ?: return@withTransaction
                app.collectionDao.upsertCollection(
                    existing.copy(
                        name = cleanName.take(80),
                        kind = kind.takeIf { it in setOf("manual", "series", "author", "theme") } ?: "manual",
                        description = description?.trim()?.take(280)?.ifBlank { null },
                        updatedAt = now,
                    ),
                )
                val links = app.collectionDao.allBookCollectionsRaw()
                    .filter { it.collectionUuid == collectionUuid }
                    .associateBy { it.bookUuid }
                state.value.allBooks.forEach { book ->
                    val link = links[book.uuid]
                    when {
                        book.uuid in bookUuids && link?.deletedAt != null ->
                            app.collectionDao.upsertBookCollection(link.copy(updatedAt = now, deletedAt = null))
                        book.uuid in bookUuids && link == null ->
                            app.collectionDao.upsertBookCollection(
                                BookCollectionCrossRef(book.uuid, collectionUuid, now, null),
                            )
                        book.uuid !in bookUuids && link != null && link.deletedAt == null ->
                            app.collectionDao.upsertBookCollection(link.copy(updatedAt = now, deletedAt = now))
                    }
                }
            }
            onFinished()
        }
    }

    fun removeCollection(collection: CollectionEntity, onFinished: () -> Unit = {}) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            app.database.withTransaction {
                app.collectionDao.upsertCollection(collection.copy(updatedAt = now, deletedAt = now))
                app.collectionDao.allBookCollectionsRaw()
                    .filter { it.collectionUuid == collection.uuid && it.deletedAt == null }
                    .forEach { link ->
                        app.collectionDao.upsertBookCollection(link.copy(updatedAt = now, deletedAt = now))
                    }
            }
            if ((filter.value as? ShelfFilter.InCollection)?.collectionUuid == collection.uuid) {
                filter.value = ShelfFilter.All
            }
            onFinished()
        }
    }

    fun toggleTag(bookUuid: String, tagUuid: String, isMember: Boolean) {
        setTagForBooks(tagUuid, setOf(bookUuid), !isMember)
    }

    fun createTag(name: String, assignToBooks: Collection<String>) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val tag = TagEntity(UUID.randomUUID().toString(), cleanName, now, now, null)
            app.collectionDao.upsertTag(tag)
            assignToBooks.forEach { app.collectionDao.upsertBookTag(BookTagCrossRef(it, tag.uuid, now, null)) }
        }
    }

    fun deleteBook(book: BookEntity) { viewModelScope.launch { deleteBookNow(book) } }

    private suspend fun deleteBookNow(book: BookEntity) {
        val now = System.currentTimeMillis()
        app.database.withTransaction {
            app.bookDao.softDelete(book.uuid, now)
            app.bookDao.deletePosition(book.uuid)
            app.annotationDao.softDeleteForBook(book.uuid, now)
            app.pdfStrokeDao.softDeleteForBook(book.uuid, now)
            app.comicPanelDao.softDeleteForBook(book.uuid, now)
            app.collectionDao.softDeleteGenresForBook(book.uuid, now)
            app.searchDao.deleteForBook(book.uuid)
        }
        File(app.booksDir, book.fileName).delete()
        book.coverPath?.let { File(it).delete() }
    }

}
