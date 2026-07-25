package app.vellum.reader.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.librarian.AiCollectionProposal
import app.vellum.reader.librarian.AiCollectionCurator
import app.vellum.reader.librarian.AiCurationBook
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharedLibraryUiState(
    val configured: Boolean = true,
    val account: SharedAccountState = SharedAccountState.Loading,
    val libraries: List<SharedLibrarySummary> = emptyList(),
    val activeLibrary: SharedLibrarySummary? = null,
    val publications: List<SharedPublication> = emptyList(),
    val allPublications: List<SharedPublication> = emptyList(),
    val collections: List<SharedCollection> = emptyList(),
    val archivedPublications: List<SharedPublication> = emptyList(),
    val localBooks: List<BookEntity> = emptyList(),
    val sharedLocalBookUuids: Set<String> = emptySet(),
    val matchingSharedBooks: Boolean = false,
    val selectedLocalBooks: Set<String> = emptySet(),
    val selectedPublicationUuids: Set<String> = emptySet(),
    val searchQuery: String = "",
    val catalogueTab: SharedCatalogueTab = SharedCatalogueTab.BOOKS,
    val catalogueView: SharedCatalogueView = SharedCatalogueView.GRID,
    val catalogueSort: SharedCatalogueSort = SharedCatalogueSort.RECENT,
    val catalogueFilter: SharedCatalogueFilter = SharedCatalogueFilter.ALL,
    val activeCollectionUuid: String? = null,
    val aiCollectionProposals: List<AiCollectionProposal> = emptyList(),
    val loading: Boolean = false,
    val emailSentTo: String? = null,
    val pendingInvitation: String? = null,
    val createdInvitation: SharedInvitation? = null,
    val transfer: SharedTransferProgress? = null,
    val message: String? = null,
    val error: String? = null,
)

class SharedLibraryViewModel(private val app: VellumApp) : ViewModel() {
    private val repository = app.sharedLibraryRepository
    private val cataloguePreferences = SharedCataloguePreferences(app)
    private val localSha256Cache = mutableMapOf<String, String>()
    private var sharedBookMatchJob: Job? = null
    private val _state = MutableStateFlow(
        SharedLibraryUiState(
            configured = repository.api.configured,
            account = repository.accountState.value,
            pendingInvitation = repository.pendingInvitation.value,
        ),
    )
    val state: StateFlow<SharedLibraryUiState> = _state

    init {
        viewModelScope.launch {
            repository.accountState.collectLatest { account ->
                _state.update {
                    if (account is SharedAccountState.SignedIn) {
                        it.copy(
                            account = account,
                            emailSentTo = null,
                            catalogueView = cataloguePreferences.readView(account.session.userId),
                            catalogueSort = cataloguePreferences.readSort(account.session.userId),
                        )
                    } else {
                        it.copy(account = account, emailSentTo = null)
                    }
                }
                if (account is SharedAccountState.SignedIn) refreshLibraries()
            }
        }
        viewModelScope.launch {
            repository.pendingInvitation.collectLatest { code ->
                _state.update { it.copy(pendingInvitation = code) }
            }
        }
        viewModelScope.launch {
            app.bookDao.observeShelf().collectLatest { books ->
                _state.update { current ->
                    current.copy(
                        localBooks = books,
                        selectedLocalBooks = current.selectedLocalBooks.intersect(books.mapTo(mutableSetOf()) { it.uuid }),
                    ).applyCatalogueView()
                }
                matchSharedLocalBooks()
            }
        }
    }

    fun sendMagicLink(email: String) = launchAction {
        repository.sendMagicLink(email)
        _state.update { it.copy(emailSentTo = email.trim().lowercase(), message = null) }
    }

    fun refreshLibraries() = launchAction {
        val libraries = repository.libraries()
        val previousId = _state.value.activeLibrary?.uuid
        val active = libraries.firstOrNull { it.uuid == previousId } ?: libraries.firstOrNull()
        _state.update { it.copy(libraries = libraries, activeLibrary = active) }
        if (active != null) refreshPublicationsInternal(active.uuid)
        else {
            _state.update {
                it.copy(
                    publications = emptyList(),
                    allPublications = emptyList(),
                    collections = emptyList(),
                    archivedPublications = emptyList(),
                    sharedLocalBookUuids = emptySet(),
                )
            }
        }
    }

    fun selectLibrary(library: SharedLibrarySummary) = launchAction {
        _state.update {
            it.copy(
                activeLibrary = library,
                publications = emptyList(),
                allPublications = emptyList(),
                collections = emptyList(),
                archivedPublications = emptyList(),
                sharedLocalBookUuids = emptySet(),
                selectedLocalBooks = emptySet(),
                selectedPublicationUuids = emptySet(),
                searchQuery = "",
                catalogueTab = SharedCatalogueTab.BOOKS,
                activeCollectionUuid = null,
                aiCollectionProposals = emptyList(),
            )
        }
        refreshPublicationsInternal(library.uuid)
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query).applyCatalogueView() }
    }

    fun submitSearch() {
        _state.update(SharedLibraryUiState::applyCatalogueView)
    }

    fun setCatalogueTab(tab: SharedCatalogueTab) {
        _state.update {
            it.copy(
                catalogueTab = tab,
                selectedPublicationUuids = emptySet(),
                activeCollectionUuid = if (tab == SharedCatalogueTab.BOOKS) it.activeCollectionUuid else null,
            ).applyCatalogueView()
        }
    }

    fun setCatalogueView(view: SharedCatalogueView) {
        val account = _state.value.account as? SharedAccountState.SignedIn ?: return
        cataloguePreferences.writeView(account.session.userId, view)
        _state.update { it.copy(catalogueView = view) }
    }

    fun setCatalogueSort(sort: SharedCatalogueSort) {
        val account = _state.value.account as? SharedAccountState.SignedIn ?: return
        cataloguePreferences.writeSort(account.session.userId, sort)
        _state.update { it.copy(catalogueSort = sort).applyCatalogueView() }
    }

    fun setCatalogueFilter(filter: SharedCatalogueFilter) {
        _state.update { it.copy(catalogueFilter = filter).applyCatalogueView() }
    }

    fun openCollection(collection: SharedCollection) {
        _state.update {
            it.copy(
                catalogueTab = SharedCatalogueTab.BOOKS,
                activeCollectionUuid = collection.uuid,
                selectedPublicationUuids = emptySet(),
            ).applyCatalogueView()
        }
    }

    fun clearCollectionFilter() {
        _state.update { it.copy(activeCollectionUuid = null).applyCatalogueView() }
    }

    fun togglePublicationSelection(uuid: String) {
        if (_state.value.activeLibrary?.role?.canPublish != true) return
        _state.update {
            val selected = if (uuid in it.selectedPublicationUuids) {
                it.selectedPublicationUuids - uuid
            } else {
                it.selectedPublicationUuids + uuid
            }
            it.copy(selectedPublicationUuids = selected)
        }
    }

    fun selectAllVisiblePublications() {
        if (_state.value.activeLibrary?.role?.canPublish != true) return
        _state.update { it.copy(selectedPublicationUuids = it.publications.mapTo(mutableSetOf()) { book -> book.uuid }) }
    }

    fun clearPublicationSelection() {
        _state.update { it.copy(selectedPublicationUuids = emptySet()) }
    }

    fun createLibrary(name: String, description: String?) = launchAction {
        val created = repository.createLibrary(name, description)
        val libraries = repository.libraries()
        _state.update {
            it.copy(
                libraries = libraries,
                activeLibrary = created,
                publications = emptyList(),
                message = "“${created.name}” is ready.",
            )
        }
    }

    fun acceptPendingInvitation(code: String = _state.value.pendingInvitation.orEmpty()) = launchAction {
        val joined = repository.acceptInvitation(code)
        val libraries = repository.libraries()
        _state.update {
            it.copy(
                libraries = libraries,
                activeLibrary = joined,
                message = "Welcome to ${joined.name}.",
            )
        }
        refreshPublicationsInternal(joined.uuid)
    }

    fun createInvitation(email: String, role: SharedLibraryRole = SharedLibraryRole.READER) =
        launchAction {
            val library = _state.value.activeLibrary
                ?: throw SharedLibraryException("Choose a shared library first.")
            val invitation = repository.createInvitation(library.uuid, email, role)
            _state.update { it.copy(createdInvitation = invitation) }
        }

    fun clearCreatedInvitation() {
        _state.update { it.copy(createdInvitation = null) }
    }

    fun toggleLocalBook(uuid: String) {
        _state.update {
            if (uuid in it.sharedLocalBookUuids) return@update it
            val selected = if (uuid in it.selectedLocalBooks) {
                it.selectedLocalBooks - uuid
            } else {
                it.selectedLocalBooks + uuid
            }
            it.copy(selectedLocalBooks = selected)
        }
    }

    fun clearLocalSelection() {
        _state.update { it.copy(selectedLocalBooks = emptySet()) }
    }

    fun selectLocalBooks(uuids: Set<String>) {
        _state.update {
            it.copy(
                selectedLocalBooks = it.selectedLocalBooks +
                    (uuids - it.sharedLocalBookUuids),
            )
        }
    }

    fun publishSelected(onFinished: () -> Unit) = launchAction {
        val library = _state.value.activeLibrary
            ?: throw SharedLibraryException("Choose a shared library first.")
        val books = _state.value.localBooks.filter { it.uuid in _state.value.selectedLocalBooks }
        if (books.isEmpty()) throw SharedLibraryException("Choose at least one book to publish.")
        val result = repository.publishBooks(library.uuid, books) { transfer ->
            _state.update { it.copy(transfer = transfer) }
        }
        _state.update {
            it.copy(
                transfer = null,
                selectedLocalBooks = emptySet(),
                message = when {
                    result.failures.isEmpty() -> "Published ${result.published} ${if (result.published == 1) "book" else "books"}."
                    result.published > 0 -> "Published ${result.published}; ${result.failures.size} need attention."
                    else -> null
                },
                error = result.failures.takeIf(List<String>::isNotEmpty)?.let(::publishFailureNotice),
            )
        }
        refreshPublicationsInternal(library.uuid)
        onFinished()
    }

    fun download(publication: SharedPublication) = launchAction {
        val existing = app.bookDao.bySharedPublication(publication.libraryUuid, publication.uuid)
        if (existing != null) {
            _state.update { it.copy(message = "Already in My Library: “${existing.title}”.") }
            return@launchAction
        }
        repository.downloadAndAdd(publication) { transfer ->
            _state.update { it.copy(transfer = transfer) }
        }
        _state.update {
            it.copy(
                transfer = null,
                message = "Added “${publication.title}” to My Library with its category and genres.",
            )
        }
    }

    fun updatePublication(
        publication: SharedPublication,
        edit: SharedPublicationEdit,
        onFinished: () -> Unit = {},
    ) = launchAction {
        repository.updatePublication(publication.uuid, edit)
        _state.update { it.copy(message = "Updated “${edit.title.trim()}”.") }
        refreshPublicationsInternal(publication.libraryUuid)
        onFinished()
    }

    fun archivePublications(
        publicationUuids: Set<String> = _state.value.selectedPublicationUuids,
        onFinished: () -> Unit = {},
    ) = launchAction {
        val library = _state.value.activeLibrary
            ?: throw SharedLibraryException("Choose a shared library first.")
        if (publicationUuids.isEmpty()) throw SharedLibraryException("Choose at least one book.")
        val changed = repository.setPublicationsArchived(library.uuid, publicationUuids, archived = true)
        _state.update {
            it.copy(
                selectedPublicationUuids = emptySet(),
                message = "$changed ${if (changed == 1) "book" else "books"} moved to Archived.",
            )
        }
        refreshLibrariesAndReselect(library.uuid)
        onFinished()
    }

    fun restorePublication(publication: SharedPublication) = launchAction {
        val changed = repository.setPublicationsArchived(
            publication.libraryUuid,
            listOf(publication.uuid),
            archived = false,
        )
        _state.update {
            it.copy(message = if (changed == 1) "Restored “${publication.title}”." else "Nothing needed restoring.")
        }
        refreshLibrariesAndReselect(publication.libraryUuid)
    }

    fun saveCollection(
        collectionUuid: String?,
        name: String,
        kind: String,
        description: String?,
        publicationUuids: Set<String>,
        onFinished: () -> Unit = {},
    ) = launchAction {
        val library = _state.value.activeLibrary
            ?: throw SharedLibraryException("Choose a shared library first.")
        repository.upsertCollection(
            libraryUuid = library.uuid,
            collectionUuid = collectionUuid,
            name = name,
            kind = kind,
            description = description,
            publicationUuids = publicationUuids,
        )
        _state.update {
            it.copy(
                selectedPublicationUuids = emptySet(),
                message = "Saved “${name.trim()}”.",
            )
        }
        refreshPublicationsInternal(library.uuid)
        onFinished()
    }

    fun archiveCollection(collection: SharedCollection, onFinished: () -> Unit = {}) = launchAction {
        repository.archiveCollection(collection.libraryUuid, collection.uuid)
        _state.update { it.copy(message = "Removed “${collection.name}” from shared collections.") }
        refreshPublicationsInternal(collection.libraryUuid)
        onFinished()
    }

    fun prepareAiCollectionReview(onFinished: () -> Unit = {}) = launchAction {
        val books = _state.value.allPublications.map { publication ->
            AiCurationBook(
                id = publication.uuid,
                title = publication.title,
                author = publication.author,
                category = publication.category,
                genres = publication.genres,
                seriesName = publication.seriesName,
                seriesIndex = publication.seriesIndex,
            )
        }
        if (books.size < 2) throw SharedLibraryException("Add at least two books before shaping shared shelves.")
        val remote = repository.curateLibrary(books)
        val proposals = AiCollectionCurator.acceptedProposals(books, remote.collections)
        _state.update {
            it.copy(
                aiCollectionProposals = proposals,
                message = if (proposals.isEmpty()) "The shared shelves are already in good shape." else null,
            )
        }
        onFinished()
    }

    fun clearAiCollectionReview() {
        _state.update { it.copy(aiCollectionProposals = emptyList()) }
    }

    fun applyAiCollections(
        proposalNames: Set<String>,
        onFinished: () -> Unit = {},
    ) = launchAction {
        val library = _state.value.activeLibrary
            ?: throw SharedLibraryException("Choose a shared library first.")
        val selected = _state.value.aiCollectionProposals.filter { it.name in proposalNames }
        if (selected.isEmpty()) throw SharedLibraryException("Choose at least one suggestion.")
        selected.forEach { proposal ->
            val existing = _state.value.collections.firstOrNull {
                AiCollectionCurator.matchesCollectionIdentity(proposal, it.name)
            }
            repository.upsertCollection(
                libraryUuid = library.uuid,
                collectionUuid = existing?.uuid,
                name = proposal.name,
                kind = proposal.kind.wireValue,
                description = proposal.explanation,
                publicationUuids = (existing?.publicationUuids.orEmpty() + proposal.bookUuids).toSet(),
            )
        }
        _state.update {
            it.copy(
                aiCollectionProposals = emptyList(),
                message = "Shaped ${selected.size} shared ${if (selected.size == 1) "collection" else "collections"}.",
            )
        }
        refreshPublicationsInternal(library.uuid)
        onFinished()
    }

    fun signOut() = launchAction {
        repository.signOut()
        _state.value = SharedLibraryUiState(
            configured = repository.api.configured,
            account = SharedAccountState.SignedOut,
            localBooks = _state.value.localBooks,
        )
    }

    fun clearNotice() {
        _state.update { it.copy(message = null, error = null) }
    }

    private suspend fun refreshPublicationsInternal(libraryUuid: String) {
        val allPublications = repository.publications(libraryUuid)
        val collections = repository.collections(libraryUuid)
        val canManage = _state.value.activeLibrary?.role?.canPublish == true
        val archived = if (canManage) repository.archivedPublications(libraryUuid) else emptyList()
        _state.update {
            it.copy(
                allPublications = allPublications,
                collections = collections,
                archivedPublications = archived,
                selectedPublicationUuids = it.selectedPublicationUuids.intersect(
                    allPublications.mapTo(mutableSetOf()) { publication -> publication.uuid },
                ),
            )
                .applyCatalogueView()
        }
        matchSharedLocalBooks()
    }

    private suspend fun refreshLibrariesAndReselect(libraryUuid: String) {
        val libraries = repository.libraries()
        val active = libraries.firstOrNull { it.uuid == libraryUuid }
        _state.update { it.copy(libraries = libraries, activeLibrary = active) }
        if (active != null) refreshPublicationsInternal(active.uuid)
    }

    private fun matchSharedLocalBooks() {
        sharedBookMatchJob?.cancel()
        val snapshot = _state.value
        val libraryUuid = snapshot.activeLibrary?.uuid
        if (libraryUuid == null || snapshot.allPublications.isEmpty() || snapshot.localBooks.isEmpty()) {
            _state.update {
                it.copy(
                    sharedLocalBookUuids = emptySet(),
                    matchingSharedBooks = false,
                ).applyCatalogueView()
            }
            return
        }
        val publications = snapshot.allPublications
        val books = snapshot.localBooks
        _state.update { it.copy(matchingSharedBooks = true) }
        sharedBookMatchJob = viewModelScope.launch(Dispatchers.IO) {
            val remoteBySize = publications.groupBy { it.sizeBytes }
            val remoteIds = publications.mapTo(mutableSetOf()) { it.uuid }
            val learnedFingerprints = mutableMapOf<String, String>()
            val matches = books.mapNotNullTo(mutableSetOf()) { book ->
                if (
                    book.sourceLibraryUuid == libraryUuid &&
                    book.sourcePublicationUuid in remoteIds
                ) {
                    return@mapNotNullTo book.uuid
                }
                val file = File(app.booksDir, book.fileName)
                val candidates = remoteBySize[file.takeIf(File::isFile)?.length()] ?: return@mapNotNullTo null
                val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
                val sha256 = book.contentSha256 ?: localSha256Cache.getOrPut(cacheKey) {
                    repository.api.sha256(file)
                }.also { learnedFingerprints[book.uuid] = it }
                book.uuid.takeIf { uuid -> candidates.any { it.sha256.equals(sha256, ignoreCase = true) } }
            }
            _state.update { current ->
                if (
                    current.activeLibrary?.uuid != libraryUuid ||
                    current.allPublications.mapTo(mutableSetOf()) { it.uuid } != remoteIds
                ) {
                    current
                } else {
                    current.copy(
                        sharedLocalBookUuids = matches,
                        selectedLocalBooks = current.selectedLocalBooks - matches,
                        matchingSharedBooks = false,
                    ).applyCatalogueView()
                }
            }
            if (learnedFingerprints.isNotEmpty()) {
                app.database.withTransaction {
                    learnedFingerprints.forEach { (uuid, sha256) ->
                        app.bookDao.setContentSha256(uuid, sha256)
                    }
                }
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                block()
            } catch (exception: Exception) {
                _state.update {
                    it.copy(
                        transfer = null,
                        error = exception.message ?: "Something went wrong. Please try again.",
                    )
                }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }
}

private fun SharedLibraryUiState.applyCatalogueView(): SharedLibraryUiState {
    val downloadedPublicationUuids = localBooks.mapNotNullTo(mutableSetOf()) { it.sourcePublicationUuid }
    val activeCollection = collections.firstOrNull { it.uuid == activeCollectionUuid }
    val cleanQuery = searchQuery.trim()
    val visible = allPublications.asSequence()
        .filter { publication ->
            cleanQuery.isBlank() ||
                publication.title.contains(cleanQuery, ignoreCase = true) ||
                publication.author.contains(cleanQuery, ignoreCase = true) ||
                publication.category?.contains(cleanQuery, ignoreCase = true) == true ||
                publication.seriesName?.contains(cleanQuery, ignoreCase = true) == true ||
                publication.genres.any { it.contains(cleanQuery, ignoreCase = true) } ||
                publication.collectionNames.any { it.contains(cleanQuery, ignoreCase = true) }
        }
        .filter { publication ->
            when (catalogueFilter) {
                SharedCatalogueFilter.ALL -> true
                SharedCatalogueFilter.DOWNLOADED -> publication.uuid in downloadedPublicationUuids
                SharedCatalogueFilter.NOT_DOWNLOADED -> publication.uuid !in downloadedPublicationUuids
            }
        }
        .filter { publication ->
            activeCollection == null || publication.uuid in activeCollection.publicationUuids
        }
        .let { publications ->
            when (catalogueSort) {
                SharedCatalogueSort.RECENT -> publications.sortedByDescending { it.createdAt }
                SharedCatalogueSort.TITLE -> publications.sortedBy { it.title.lowercase() }
                SharedCatalogueSort.AUTHOR -> publications.sortedWith(
                    compareBy<SharedPublication>({ it.author.lowercase() }, { it.title.lowercase() }),
                )
                SharedCatalogueSort.SERIES -> publications.sortedWith(
                    compareBy<SharedPublication>(
                        { it.seriesName?.lowercase() ?: "\uffff" },
                        { it.seriesIndex ?: Float.MAX_VALUE },
                        { it.title.lowercase() },
                    ),
                )
            }
        }
        .toList()
    return copy(publications = visible)
}

private fun publishFailureNotice(failures: List<String>): String =
    if (failures.size == 1) {
        failures.first().take(220)
    } else {
        "${failures.size} books could not be published. First issue: ${failures.first()}".take(220)
    }
