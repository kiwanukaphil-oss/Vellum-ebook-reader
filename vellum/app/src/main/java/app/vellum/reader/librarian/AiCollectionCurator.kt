package app.vellum.reader.librarian

/**
 * Keeps collection creation conservative even when the remote curator is creative.
 * Structural shelves are derived locally; model-created themes need stronger evidence.
 */
object AiCollectionCurator {
    private const val MIN_THEME_CONFIDENCE = 0.90f
    private const val MIN_INFERRED_SERIES_CONFIDENCE = 0.95f
    private const val MAX_THEMATIC_COLLECTIONS = 6

    fun structuralProposals(books: List<AiCurationBook>): List<AiCollectionProposal> {
        val series = books
            .filter { !it.seriesName.isNullOrBlank() }
            .groupBy { seriesFamilyKey(it.seriesName.orEmpty()) }
            .values
            .filter { it.size >= 2 }
            .map { members ->
                val name = preferredSeriesName(members)
                AiCollectionProposal(
                    name = name,
                    kind = AiCollectionKind.SERIES,
                    bookUuids = members
                        .sortedWith(compareBy({ it.seriesIndex ?: Float.MAX_VALUE }, { it.title }))
                        .map { it.id },
                    confidence = 1f,
                    explanation = "Books carrying the same series metadata.",
                )
            }

        val authors = books
            .filterNot { it.author.isPlaceholderAuthor() }
            .groupBy { authorKey(it.author) }
            .values
            .filter { it.size >= 2 }
            .map { members ->
                val name = preferredAuthorName(members)
                AiCollectionProposal(
                    name = name,
                    kind = AiCollectionKind.AUTHOR,
                    bookUuids = members.sortedBy { it.title }.map { it.id },
                    confidence = 1f,
                    explanation = "A shelf for books by $name.",
                )
            }

        return (series + authors).sortedWith(
            compareBy<AiCollectionProposal>({ it.kind.ordinal }, { it.name.lowercase() }),
        )
    }

    fun acceptedProposals(
        books: List<AiCurationBook>,
        remote: List<AiCollectionProposal>,
    ): List<AiCollectionProposal> {
        val booksById = books.associateBy { it.id }
        val structural = structuralProposals(books)
        val acceptedRemote = remote
            .asSequence()
            .map { it.copy(bookUuids = it.bookUuids.distinct()) }
            .filter { proposal ->
                proposal.name.isNotBlank() &&
                    proposal.name.length <= 60 &&
                    proposal.bookUuids.all(booksById::containsKey) &&
                    normalize(proposal.name) !in excludedBroadNames
            }
            .filter { proposal ->
                val members = proposal.bookUuids.mapNotNull(booksById::get)
                when (proposal.kind) {
                    AiCollectionKind.AUTHOR ->
                        members.size >= 2 &&
                            members.none { it.author.isPlaceholderAuthor() } &&
                            members.map { authorKey(it.author) }.distinct().size == 1

                    AiCollectionKind.SERIES -> {
                        val knownSeries = members
                            .mapNotNull { it.seriesName?.takeIf(String::isNotBlank)?.let(::seriesFamilyKey) }
                            .distinct()
                        members.size >= 2 &&
                            knownSeries.size <= 1 &&
                            (
                                knownSeries.size == 1 && members.all { !it.seriesName.isNullOrBlank() } ||
                                    proposal.confidence >= MIN_INFERRED_SERIES_CONFIDENCE
                                )
                    }

                    AiCollectionKind.THEME ->
                        members.size >= 3 && proposal.confidence >= MIN_THEME_CONFIDENCE
                }
            }
            .map { proposal ->
                when (proposal.kind) {
                    AiCollectionKind.AUTHOR -> proposal.copy(
                        name = preferredAuthorName(proposal.bookUuids.mapNotNull(booksById::get)),
                    )
                    else -> proposal
                }
            }
            .let { proposals ->
                val materialized = proposals.toList()
                materialized.filter { it.kind != AiCollectionKind.THEME } +
                    materialized.filter { it.kind == AiCollectionKind.THEME }
                        .sortedByDescending { it.confidence }
                        .take(MAX_THEMATIC_COLLECTIONS)
            }

        val byName = linkedMapOf<String, AiCollectionProposal>()
        (structural + acceptedRemote)
            .map { proposal ->
                if (proposal.kind == AiCollectionKind.SERIES) {
                    completeSeriesMembership(proposal, books, booksById)
                } else {
                    proposal
                }
            }
            .forEach { proposal ->
            val key = collectionIdentityKey(proposal)
            val existing = byName[key]
            if (existing == null) {
                byName[key] = proposal
            } else if (existing.kind == proposal.kind) {
                byName[key] = existing.copy(
                    bookUuids = (existing.bookUuids + proposal.bookUuids).distinct(),
                    confidence = maxOf(existing.confidence, proposal.confidence),
                    explanation = if (existing.confidence >= proposal.confidence) {
                        existing.explanation
                    } else {
                        proposal.explanation
                    },
                )
            }
        }
        return byName.values.toList()
    }

    internal fun matchesCollectionIdentity(
        proposal: AiCollectionProposal,
        existingName: String,
    ): Boolean = when (proposal.kind) {
        AiCollectionKind.AUTHOR -> authorCollectionKey(existingName) == authorCollectionKey(proposal.name)
        AiCollectionKind.SERIES -> seriesFamilyKey(existingName) == seriesFamilyKey(proposal.name)
        AiCollectionKind.THEME -> normalize(existingName) == normalize(proposal.name)
    }

    internal fun normalize(value: String): String =
        value.trim()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun collectionIdentityKey(proposal: AiCollectionProposal): String = when (proposal.kind) {
        AiCollectionKind.AUTHOR -> "author:${authorCollectionKey(proposal.name)}"
        AiCollectionKind.SERIES -> "series:${seriesFamilyKey(proposal.name)}"
        AiCollectionKind.THEME -> "theme:${normalize(proposal.name)}"
    }

    private fun completeSeriesMembership(
        proposal: AiCollectionProposal,
        books: List<AiCurationBook>,
        booksById: Map<String, AiCurationBook>,
    ): AiCollectionProposal {
        val familyKey = seriesFamilyKey(proposal.name)
        if (familyKey.isBlank()) return proposal
        val currentMembers = proposal.bookUuids.mapNotNull(booksById::get)
        val authorKeys = currentMembers
            .filterNot { it.author.isPlaceholderAuthor() }
            .mapTo(mutableSetOf()) { authorKey(it.author) }
        if (authorKeys.isEmpty()) return proposal
        val completedIds = books.asSequence()
            .filter { authorKey(it.author) in authorKeys }
            .filter { book ->
                book.seriesName?.let(::seriesFamilyKey) == familyKey ||
                    titleContainsSeriesFamily(book.title, familyKey)
            }
            .map { it.id }
            .toList()
        return proposal.copy(bookUuids = (proposal.bookUuids + completedIds).distinct())
    }

    private fun titleContainsSeriesFamily(title: String, familyKey: String): Boolean {
        if (familyKey.length < 5) return false
        return " ${normalize(title)} ".contains(" $familyKey ")
    }

    private fun preferredSeriesName(members: List<AiCurationBook>): String =
        members.mapNotNull { it.seriesName?.trim()?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>>({ it.value }, { it.key.length }))
            ?.key
            .orEmpty()

    private fun preferredAuthorName(members: List<AiCurationBook>): String {
        val preferred = members.map { it.author.trim() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { ',' in it.key }
                    .thenBy { it.key.length },
            )
            .first()
            .key
        val parts = preferred.split(',', limit = 2).map(String::trim).filter(String::isNotBlank)
        return if (parts.size == 2) "${parts[1]} ${parts[0]}" else preferred
    }

    private fun authorCollectionKey(value: String): String {
        val withoutLabel = normalize(value).removePrefix("books by ").trim()
        return authorKey(withoutLabel)
    }

    private fun authorKey(value: String): String {
        val parts = value.split(',', limit = 2).map(::normalize).filter(String::isNotBlank)
        return if (parts.size == 2) "${parts[1]} ${parts[0]}" else normalize(value)
    }

    private fun seriesFamilyKey(value: String): String {
        var key = normalize(value)
        key = key.removePrefix("the ").trim()
        val suffixes = listOf(" trilogy", " series", " saga", " cycle", " chronicles", " collection")
        suffixes.firstOrNull(key::endsWith)?.let { key = key.removeSuffix(it).trim() }
        return key
    }

    private fun String.isPlaceholderAuthor(): Boolean {
        val clean = trim()
        return clean.isBlank() ||
            clean.equals("Unknown author", ignoreCase = true) ||
            clean.equals("PDF", ignoreCase = true) ||
            clean.equals("Various", ignoreCase = true) ||
            clean.equals("Various authors", ignoreCase = true)
    }

    private val excludedBroadNames = (
        listOf("Fiction", "Non-fiction", "Comics & Manga", "Essays & Poetry") +
            VellumAiTaxonomy.genres
        ).mapTo(mutableSetOf(), ::normalize)
}
