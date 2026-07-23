package app.vellum.reader.shared

import android.net.Uri
import androidx.room.withTransaction
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.BookGenreCrossRef
import app.vellum.reader.core.data.GenreEntity
import app.vellum.reader.library.BookImporter
import app.vellum.reader.librarian.AiEnrichmentRequest
import app.vellum.reader.librarian.AiEnrichmentResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SharedPublishResult(
    val published: Int,
    val failures: List<String>,
)

class SharedLibraryRepository(
    private val app: VellumApp,
    val api: SharedLibraryApi = SharedLibraryApi(),
    private val sessionStore: SecureSessionStore = SecureSessionStore(app),
) {
    private val sessionMutex = Mutex()
    private val _accountState = MutableStateFlow<SharedAccountState>(
        sessionStore.read()?.let(SharedAccountState::SignedIn) ?: SharedAccountState.SignedOut,
    )
    val accountState: StateFlow<SharedAccountState> = _accountState

    private val _pendingInvitation = MutableStateFlow(sessionStore.readPendingInvitation())
    val pendingInvitation: StateFlow<String?> = _pendingInvitation

    suspend fun sendMagicLink(email: String) = api.sendMagicLink(email)

    fun completeAuthCallback(uri: Uri) {
        val session = api.sessionFromCallback(uri)
        sessionStore.write(session)
        _accountState.value = SharedAccountState.SignedIn(session)
    }

    fun rememberInvitation(code: String) {
        val clean = code.trim()
        if (clean.isBlank()) return
        sessionStore.writePendingInvitation(clean)
        _pendingInvitation.value = clean
    }

    fun clearInvitation() {
        sessionStore.writePendingInvitation(null)
        _pendingInvitation.value = null
    }

    suspend fun signOut() {
        val session = (_accountState.value as? SharedAccountState.SignedIn)?.session
        try {
            if (session != null) api.signOut(session.accessToken)
        } finally {
            clearSession()
        }
    }

    private fun clearSession() {
        sessionStore.clear()
        _accountState.value = SharedAccountState.SignedOut
    }

    suspend fun libraries(): List<SharedLibrarySummary> = authenticated(api::libraries)

    suspend fun publications(
        libraryUuid: String,
        query: String = "",
    ): List<SharedPublication> =
        authenticated { api.publications(libraryUuid, query, it) }

    suspend fun createLibrary(name: String, description: String?): SharedLibrarySummary =
        authenticated { api.createLibrary(name, description, it) }

    suspend fun createInvitation(
        libraryUuid: String,
        inviteeEmail: String,
        role: SharedLibraryRole,
    ): SharedInvitation =
        authenticated { api.createInvitation(libraryUuid, inviteeEmail, role, it) }

    suspend fun acceptInvitation(code: String): SharedLibrarySummary =
        authenticated { token ->
            api.acceptInvitation(code, token).also { clearInvitation() }
        }

    suspend fun publishBooks(
        libraryUuid: String,
        books: List<BookEntity>,
        onProgress: (SharedTransferProgress) -> Unit,
    ): SharedPublishResult {
        var published = 0
        val failures = mutableListOf<String>()
        val preparedBooks = books.map { book ->
            app.aiLibrarian.organize(book.uuid)
            app.bookDao.byUuid(book.uuid) ?: book
        }
        val genres = app.collectionDao.allGenresRaw().associateBy { it.uuid }
        val links = app.collectionDao.allBookGenresRaw()
            .filter { it.deletedAt == null }
            .groupBy { it.bookUuid }
        for ((index, book) in preparedBooks.withIndex()) {
            val file = File(app.booksDir, book.fileName)
            if (!file.isFile) {
                failures += "${book.title}: the local file is missing"
                continue
            }
            try {
                onProgress(
                    SharedTransferProgress(
                        publicationUuid = book.uuid,
                        title = book.title,
                        fraction = index.toFloat() / preparedBooks.size.coerceAtLeast(1),
                        message = "Preparing “${book.title}”…",
                    ),
                )
                val digest = api.sha256(file)
                app.bookDao.setContentSha256(book.uuid, digest)
                val publicationUuid = authenticated { token ->
                    api.beginPublication(
                        libraryUuid = libraryUuid,
                        title = book.title,
                        author = book.author,
                        format = file.extension.lowercase(),
                        category = book.category,
                        genres = links[book.uuid].orEmpty().mapNotNull { genres[it.genreUuid]?.name },
                        seriesName = book.seriesName,
                        seriesIndex = book.seriesIndex,
                        originalFileName = file.name,
                        sha256 = digest,
                        sizeBytes = file.length(),
                        accessToken = token,
                    )
                }
                onProgress(
                    SharedTransferProgress(
                        publicationUuid = publicationUuid,
                        title = book.title,
                        fraction = null,
                        message = "Publishing “${book.title}”…",
                    ),
                )
                authenticated { token ->
                    api.uploadPublication(
                        libraryUuid = libraryUuid,
                        publicationUuid = publicationUuid,
                        file = file,
                        sha256 = digest,
                        cover = book.coverPath?.let(::File),
                        accessToken = token,
                    )
                }
                published++
            } catch (exception: Exception) {
                failures += "${book.title}: ${exception.message ?: "upload failed"}"
            }
        }
        return SharedPublishResult(published = published, failures = failures)
    }

    suspend fun downloadAndAdd(
        publication: SharedPublication,
        onProgress: (SharedTransferProgress) -> Unit,
    ): BookEntity {
        app.bookDao.bySharedPublication(publication.libraryUuid, publication.uuid)?.let { return it }
        val directory = File(app.cacheDir, "shared-downloads").apply { mkdirs() }
        val temp = File.createTempFile("vellum-shared-", ".${publication.format}", directory)
        try {
            onProgress(
                SharedTransferProgress(
                    publicationUuid = publication.uuid,
                    title = publication.title,
                    fraction = null,
                    message = "Downloading “${publication.title}”…",
                ),
            )
            authenticated { token -> api.downloadPublication(publication, temp, token) }
            val knownUuids = app.bookDao.allActive().mapTo(mutableSetOf()) { it.uuid }
            val imported = BookImporter(app).importFromFile(temp, publication.title, organize = false)
                ?: throw SharedLibraryException("Vellum could not open the downloaded book.")
            val isNewImport = imported.uuid !in knownUuids
            val now = System.currentTimeMillis()
            app.database.withTransaction {
                if (isNewImport) {
                    app.bookDao.updateMetadata(
                        uuid = imported.uuid,
                        title = publication.title,
                        author = publication.author,
                        seriesName = publication.seriesName,
                        seriesIndex = publication.seriesIndex,
                        updatedAt = now,
                    )
                }
                if (isNewImport || (imported.category == null && publication.category != null)) {
                    app.bookDao.updateCategory(imported.uuid, publication.category, now)
                }
                app.bookDao.setSharedProvenance(
                    imported.uuid,
                    publication.libraryUuid,
                    publication.uuid,
                    now,
                )
                app.bookDao.setContentSha256(imported.uuid, publication.sha256)
                applyGenres(imported.uuid, publication.genres, now)
            }
            return app.bookDao.byUuid(imported.uuid) ?: imported
        } finally {
            temp.delete()
        }
    }

    suspend fun accessTokenForImages(): String? =
        runCatching { validSession().accessToken }.getOrNull()

    suspend fun enrichBook(request: AiEnrichmentRequest): AiEnrichmentResult =
        authenticated { api.enrichBook(request, it) }

    private suspend fun applyGenres(bookUuid: String, names: List<String>, now: Long) {
        if (names.isEmpty()) return
        val existing = app.collectionDao.allGenresRaw()
            .filter { it.deletedAt == null }
            .associateBy { normalize(it.name) }
        for (name in names.distinctBy(::normalize)) {
            val genre = existing[normalize(name)] ?: GenreEntity(
                uuid = UUID.nameUUIDFromBytes("vellum-genre:$name".toByteArray()).toString(),
                name = name.trim(),
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ).also { app.collectionDao.upsertGenre(it) }
            app.collectionDao.upsertBookGenre(
                BookGenreCrossRef(
                    bookUuid = bookUuid,
                    genreUuid = genre.uuid,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private suspend fun <T> authenticated(block: suspend (String) -> T): T =
        block(validSession().accessToken)

    private suspend fun validSession(): SharedAuthSession = sessionMutex.withLock {
        val current = (_accountState.value as? SharedAccountState.SignedIn)?.session
            ?: throw SharedLibraryException("Sign in to open your shared libraries.")
        if (current.expiresAtEpochSeconds > System.currentTimeMillis() / 1000L + 90L) return current
        return try {
            api.refreshSession(current).also {
                sessionStore.write(it)
                _accountState.value = SharedAccountState.SignedIn(it)
            }
        } catch (exception: Exception) {
            clearSession()
            throw SharedLibraryException("Your session has expired. Please sign in again.")
        }
    }
}
