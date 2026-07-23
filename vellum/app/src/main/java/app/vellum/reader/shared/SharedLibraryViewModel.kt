package app.vellum.reader.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
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
    val localBooks: List<BookEntity> = emptyList(),
    val sharedLocalBookUuids: Set<String> = emptySet(),
    val matchingSharedBooks: Boolean = false,
    val selectedLocalBooks: Set<String> = emptySet(),
    val searchQuery: String = "",
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
                _state.update { it.copy(account = account, emailSentTo = null) }
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
                    )
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
                sharedLocalBookUuids = emptySet(),
                selectedLocalBooks = emptySet(),
                searchQuery = "",
            )
        }
        refreshPublicationsInternal(library.uuid)
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun submitSearch() = launchAction {
        _state.value.activeLibrary?.let { refreshPublicationsInternal(it.uuid) }
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
        val query = _state.value.searchQuery
        val allPublications = repository.publications(libraryUuid)
        val publications = if (query.isBlank()) {
            allPublications
        } else {
            repository.publications(libraryUuid, query)
        }
        _state.update {
            it.copy(
                publications = publications,
                allPublications = allPublications,
            )
        }
        matchSharedLocalBooks()
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
                )
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
                    )
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

private fun publishFailureNotice(failures: List<String>): String =
    if (failures.size == 1) {
        failures.first().take(220)
    } else {
        "${failures.size} books could not be published. First issue: ${failures.first()}".take(220)
    }
