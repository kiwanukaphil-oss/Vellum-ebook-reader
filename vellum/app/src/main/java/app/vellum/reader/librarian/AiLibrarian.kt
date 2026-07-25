package app.vellum.reader.librarian

import androidx.room.withTransaction
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.AiMetadataSuggestionEntity
import app.vellum.reader.core.data.BookCollectionCrossRef
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.BookGenreCrossRef
import app.vellum.reader.core.data.CollectionEntity
import app.vellum.reader.core.data.GenreEntity
import app.vellum.reader.library.BookCategories
import app.vellum.reader.shared.SharedAccountState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

class AiLibrarian(private val app: VellumApp) {
    val suggestions = app.aiMetadataDao.observeAll()

    private val _processing = MutableStateFlow<Set<String>>(emptySet())
    val processing: StateFlow<Set<String>> = _processing

    fun organizeInBackground(bookUuid: String) {
        app.appScope.launch { organize(bookUuid = bookUuid, notify = true) }
    }

    suspend fun organize(
        bookUuid: String,
        force: Boolean = false,
        notify: Boolean = false,
    ): AiOrganizeOutcome {
        if (bookUuid in _processing.value) return AiOrganizeOutcome.AlreadyOrganized
        if (app.sharedLibraryRepository.accountState.value !is SharedAccountState.SignedIn) {
            return AiOrganizeOutcome.SignInRequired
        }
        if (!app.sharedLibraryRepository.api.configured) {
            return AiOrganizeOutcome.Failed("AI Librarian is not configured in this build.")
        }
        val existingSuggestion = app.aiMetadataDao.latestForBook(bookUuid)
        if (!force && existingSuggestion != null && existingSuggestion.status != "reverted") {
            return AiOrganizeOutcome.AlreadyOrganized
        }
        val book = app.bookDao.byUuid(bookUuid) ?: return AiOrganizeOutcome.AlreadyOrganized
        _processing.value += bookUuid
        return try {
            val currentGenres = genreNamesFor(bookUuid)
            val excerpt = app.searchDao.excerptForBook(bookUuid)
                .joinToString("\n")
                .replace(Regex("\\s+"), " ")
                .take(MAX_EXCERPT_CHARACTERS)
            val result = app.sharedLibraryRepository.enrichBook(
                AiEnrichmentRequest(
                    title = book.title,
                    author = book.author,
                    fileName = book.fileName,
                    format = book.format,
                    currentCategory = book.category,
                    currentGenres = currentGenres,
                    excerpt = excerpt,
                ),
            )
            validate(result)
            val suggestion = result.toEntity(bookUuid)
            app.aiMetadataDao.upsert(suggestion)
            if (result.confidence >= VellumAiTaxonomy.AUTO_APPLY_CONFIDENCE) {
                apply(suggestion.uuid, conservative = true)
                if (notify) app.importNotices.tryEmit("Organised “${result.title}”")
                AiOrganizeOutcome.Applied
            } else {
                if (notify) app.importNotices.tryEmit("“${book.title}” has an organisation suggestion to review")
                AiOrganizeOutcome.NeedsReview
            }
        } catch (exception: Exception) {
            AiOrganizeOutcome.Failed(exception.message ?: "The librarian could not organise this book.")
        } finally {
            _processing.value -= bookUuid
        }
    }

    suspend fun organizeAll(
        bookUuids: List<String>,
        force: Boolean = false,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): AiOrganizeSummary {
        var applied = 0
        var review = 0
        val uniqueBookUuids = bookUuids.distinct()
        uniqueBookUuids.forEachIndexed { index, uuid ->
            when (organize(uuid, force = force)) {
                AiOrganizeOutcome.Applied -> applied++
                AiOrganizeOutcome.NeedsReview -> review++
                else -> Unit
            }
            onProgress(index + 1, uniqueBookUuids.size)
        }
        val (curatedCollections, thematicCurationAvailable) = curateCollections()
        return AiOrganizeSummary(
            appliedBooks = applied,
            reviewBooks = review,
            curatedCollections = curatedCollections,
            thematicCurationAvailable = thematicCurationAvailable,
        )
    }

    suspend fun apply(suggestionUuid: String, conservative: Boolean = false): Boolean {
        val suggestion = app.aiMetadataDao.byUuid(suggestionUuid) ?: return false
        if (suggestion.status != "pending") return false
        val book = app.bookDao.byUuid(suggestion.bookUuid) ?: return false
        val beforeGenres = genreNamesFor(book.uuid)
        val proposedGenres = decodeNames(suggestion.proposedGenresJson)
        val appliedTitle = if (conservative && !book.title.needsAutomaticCleanup()) {
            book.title
        } else {
            suggestion.proposedTitle
        }
        val appliedAuthor = if (conservative && !book.author.isPlaceholderAuthor()) {
            book.author
        } else {
            suggestion.proposedAuthor
        }
        val appliedCategory = if (conservative) book.category ?: suggestion.proposedCategory else suggestion.proposedCategory
        val appliedGenres = if (conservative) {
            (beforeGenres + proposedGenres).distinctBy(::normalize)
        } else {
            proposedGenres
        }
        val appliedSeriesName =
            if (conservative) book.seriesName ?: suggestion.proposedSeriesName else suggestion.proposedSeriesName
        val appliedSeriesIndex =
            if (conservative && book.seriesName != null) book.seriesIndex else suggestion.proposedSeriesIndex
        val now = System.currentTimeMillis()
        app.database.withTransaction {
            app.bookDao.updateMetadata(
                uuid = book.uuid,
                title = appliedTitle,
                author = appliedAuthor,
                seriesName = appliedSeriesName,
                seriesIndex = appliedSeriesIndex,
                updatedAt = now,
            )
            app.bookDao.updateCategory(book.uuid, appliedCategory, now)
            replaceGenres(book.uuid, appliedGenres, now)
            app.aiMetadataDao.markApplied(
                uuid = suggestion.uuid,
                beforeTitle = book.title,
                beforeAuthor = book.author,
                beforeCategory = book.category,
                beforeGenresJson = encodeNames(beforeGenres),
                beforeSeriesName = book.seriesName,
                beforeSeriesIndex = book.seriesIndex,
                appliedTitle = appliedTitle,
                appliedAuthor = appliedAuthor,
                appliedCategory = appliedCategory,
                appliedGenresJson = encodeNames(appliedGenres),
                appliedSeriesName = appliedSeriesName,
                appliedSeriesIndex = appliedSeriesIndex,
                appliedAt = now,
            )
        }
        return true
    }

    suspend fun undo(suggestionUuid: String): Boolean {
        val suggestion = app.aiMetadataDao.byUuid(suggestionUuid) ?: return false
        if (suggestion.status != "applied" || suggestion.beforeTitle == null || suggestion.beforeAuthor == null) return false
        val book = app.bookDao.byUuid(suggestion.bookUuid) ?: return false
        val currentGenres = genreNamesFor(book.uuid)
        val stillMatchesApplied =
            book.title == suggestion.appliedTitle &&
                book.author == suggestion.appliedAuthor &&
                book.category == suggestion.appliedCategory &&
                book.seriesName == suggestion.appliedSeriesName &&
                book.seriesIndex == suggestion.appliedSeriesIndex &&
                currentGenres.normalizedSet() == decodeNames(suggestion.appliedGenresJson).normalizedSet()
        if (!stillMatchesApplied) return false

        val now = System.currentTimeMillis()
        app.database.withTransaction {
            app.bookDao.updateMetadata(
                uuid = book.uuid,
                title = suggestion.beforeTitle,
                author = suggestion.beforeAuthor,
                seriesName = suggestion.beforeSeriesName,
                seriesIndex = suggestion.beforeSeriesIndex,
                updatedAt = now,
            )
            app.bookDao.updateCategory(book.uuid, suggestion.beforeCategory, now)
            replaceGenres(book.uuid, decodeNames(suggestion.beforeGenresJson), now)
            app.aiMetadataDao.markReverted(suggestion.uuid, now)
        }
        return true
    }

    suspend fun dismiss(suggestionUuid: String) {
        app.aiMetadataDao.dismiss(suggestionUuid)
    }

    private suspend fun curateCollections(): Pair<Int, Boolean> {
        val books = app.bookDao.allActive()
        if (books.size < 2) return 0 to true
        val genres = app.collectionDao.allGenresRaw()
            .filter { it.deletedAt == null }
            .associateBy { it.uuid }
        val genreNamesByBook = app.collectionDao.allBookGenresRaw()
            .asSequence()
            .filter { it.deletedAt == null }
            .groupBy({ it.bookUuid }, { genres[it.genreUuid]?.name })
            .mapValues { (_, names) -> names.filterNotNull().distinct() }
        val curationBooks = books.map { book ->
            AiCurationBook(
                id = book.uuid,
                title = book.title,
                author = book.author,
                category = book.category,
                genres = genreNamesByBook[book.uuid].orEmpty(),
                seriesName = book.seriesName,
                seriesIndex = book.seriesIndex,
            )
        }
        val remoteResult = runCatching {
            app.sharedLibraryRepository.curateLibrary(curationBooks)
        }
        val proposals = AiCollectionCurator.acceptedProposals(
            books = curationBooks,
            remote = remoteResult.getOrNull()?.collections.orEmpty(),
        )
        return applyCollectionProposals(proposals) to remoteResult.isSuccess
    }

    private suspend fun applyCollectionProposals(proposals: List<AiCollectionProposal>): Int {
        if (proposals.isEmpty()) return 0
        val existingCollections = app.collectionDao.allCollectionsRaw()
        val existingLinks = app.collectionDao.allBookCollectionsRaw()
            .associateByTo(mutableMapOf()) { it.bookUuid to it.collectionUuid }
        val now = System.currentTimeMillis()
        var changedCollections = 0
        app.database.withTransaction {
            proposals.forEach { proposal ->
                val key = AiCollectionCurator.normalize(proposal.name)
                val proposalMembers = proposal.bookUuids.toSet()
                val matchingCollections = existingCollections
                    .filter { AiCollectionCurator.matchesCollectionIdentity(proposal, it.name) }
                    .filter { collection ->
                        AiCollectionCurator.normalize(collection.name) == key ||
                            existingLinks.values
                                .filter { it.collectionUuid == collection.uuid && it.deletedAt == null }
                                .all { it.bookUuid in proposalMembers }
                    }
                val existing = matchingCollections
                    .firstOrNull { it.deletedAt == null && AiCollectionCurator.normalize(it.name) == key }
                    ?: matchingCollections.firstOrNull { it.deletedAt == null }
                    ?: matchingCollections.firstOrNull()
                val collectionUuid = existing?.uuid ?: UUID.nameUUIDFromBytes(
                    "vellum-ai-collection:$key".toByteArray(),
                ).toString()
                var changed = existing == null ||
                    existing.deletedAt != null ||
                    existing.name.trim() != proposal.name.trim()
                if (changed) {
                    app.collectionDao.upsertCollection(
                        CollectionEntity(
                            uuid = collectionUuid,
                            name = proposal.name.trim(),
                            kind = proposal.kind.wireValue,
                            description = proposal.explanation,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                            deletedAt = null,
                        ),
                    )
                }
                val duplicateCollections = matchingCollections.filter { it.uuid != collectionUuid }
                val completedMembers = (
                    proposal.bookUuids +
                        duplicateCollections.flatMap { duplicate ->
                            existingLinks.values
                                .filter { it.collectionUuid == duplicate.uuid && it.deletedAt == null }
                                .map { it.bookUuid }
                        }
                    ).distinct()
                completedMembers.forEach { bookUuid ->
                    val current = existingLinks[bookUuid to collectionUuid]
                    if (current == null || current.deletedAt != null) {
                        val restored = BookCollectionCrossRef(
                            bookUuid = bookUuid,
                            collectionUuid = collectionUuid,
                            updatedAt = now,
                            deletedAt = null,
                        )
                        app.collectionDao.upsertBookCollection(restored)
                        existingLinks[bookUuid to collectionUuid] = restored
                        changed = true
                    }
                }
                duplicateCollections.forEach { duplicate ->
                    existingLinks.values
                        .filter { it.collectionUuid == duplicate.uuid && it.deletedAt == null }
                        .toList()
                        .forEach { link ->
                            val archivedLink = link.copy(updatedAt = now, deletedAt = now)
                            app.collectionDao.upsertBookCollection(archivedLink)
                            existingLinks[link.bookUuid to link.collectionUuid] = archivedLink
                        }
                    if (duplicate.deletedAt == null) {
                        app.collectionDao.upsertCollection(
                            duplicate.copy(updatedAt = now, deletedAt = now),
                        )
                        changed = true
                    }
                }
                if (changed) changedCollections++
            }
        }
        return changedCollections
    }

    private suspend fun genreNamesFor(bookUuid: String): List<String> {
        val genres = app.collectionDao.allGenresRaw().associateBy { it.uuid }
        return app.collectionDao.allBookGenresRaw()
            .asSequence()
            .filter { it.bookUuid == bookUuid && it.deletedAt == null }
            .mapNotNull { genres[it.genreUuid]?.takeIf { genre -> genre.deletedAt == null }?.name }
            .distinctBy(::normalize)
            .sorted()
            .toList()
    }

    private suspend fun replaceGenres(bookUuid: String, names: List<String>, now: Long) {
        val existing = app.collectionDao.allGenresRaw().associateBy { normalize(it.name) }
        val desiredIds = names.distinctBy(::normalize).mapTo(mutableSetOf()) { name ->
            val normalized = normalize(name)
            val current = existing[normalized]
            val genre = if (current == null || current.deletedAt != null) {
                GenreEntity(
                    uuid = current?.uuid ?: UUID.nameUUIDFromBytes("vellum-genre:$name".toByteArray()).toString(),
                    name = name.trim(),
                    createdAt = current?.createdAt ?: now,
                    updatedAt = now,
                    deletedAt = null,
                ).also { app.collectionDao.upsertGenre(it) }
            } else {
                current
            }
            genre.uuid
        }
        val allKnownIds = app.collectionDao.allGenresRaw().mapTo(mutableSetOf()) { it.uuid }
        allKnownIds += app.collectionDao.allBookGenresRaw()
            .filter { it.bookUuid == bookUuid }
            .map { it.genreUuid }
        allKnownIds.forEach { genreUuid ->
            app.collectionDao.upsertBookGenre(
                BookGenreCrossRef(
                    bookUuid = bookUuid,
                    genreUuid = genreUuid,
                    updatedAt = now,
                    deletedAt = if (genreUuid in desiredIds) null else now,
                ),
            )
        }
    }

    private fun validate(result: AiEnrichmentResult) {
        require(result.title.isNotBlank() && result.title.length <= 300)
        require(result.author.isNotBlank() && result.author.length <= 300)
        require(result.category in BookCategories.all)
        require(result.genres.size in 1..3 && result.genres.all { it in VellumAiTaxonomy.genres })
        require(result.confidence in 0f..1f)
    }

    private fun AiEnrichmentResult.toEntity(bookUuid: String) = AiMetadataSuggestionEntity(
        uuid = UUID.randomUUID().toString(),
        bookUuid = bookUuid,
        status = "pending",
        proposedTitle = title,
        proposedAuthor = author,
        proposedCategory = category,
        proposedGenresJson = encodeNames(genres),
        proposedSeriesName = seriesName,
        proposedSeriesIndex = seriesIndex,
        confidence = confidence,
        explanation = explanation,
        model = model,
        taxonomyVersion = taxonomyVersion,
        beforeTitle = null,
        beforeAuthor = null,
        beforeCategory = null,
        beforeGenresJson = null,
        beforeSeriesName = null,
        beforeSeriesIndex = null,
        appliedTitle = null,
        appliedAuthor = null,
        appliedCategory = null,
        appliedGenresJson = null,
        appliedSeriesName = null,
        appliedSeriesIndex = null,
        createdAt = System.currentTimeMillis(),
        appliedAt = null,
        revertedAt = null,
    )

    companion object {
        private const val MAX_EXCERPT_CHARACTERS = 5_000

        fun encodeNames(names: List<String>): String = JSONArray(names).toString()

        fun decodeNames(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).trim().takeIf(String::isNotBlank)
                }
            }.getOrDefault(emptyList())
        }

        private fun normalize(value: String): String =
            value.trim().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        private fun List<String>.normalizedSet(): Set<String> = mapTo(mutableSetOf(), ::normalize)

        private fun String.isPlaceholderAuthor(): Boolean =
            isBlank() || equals("Unknown author", ignoreCase = true) || equals("PDF", ignoreCase = true)

        private fun String.needsAutomaticCleanup(): Boolean =
            isBlank() ||
                startsWith("Untitled", ignoreCase = true) ||
                contains('_') ||
                contains(".com_", ignoreCase = true) ||
                contains("oceanofpdf", ignoreCase = true) ||
                Regex("\\.(epub|pdf|cbz|cbr)$", RegexOption.IGNORE_CASE).containsMatchIn(this)
    }
}
