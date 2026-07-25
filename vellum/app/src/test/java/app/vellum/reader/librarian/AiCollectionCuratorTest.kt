package app.vellum.reader.librarian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCollectionCuratorTest {

    @Test
    fun structuralProposalsCreateSeriesAndRepeatAuthorShelves() {
        val books = listOf(
            book("earthsea-1", "A Wizard of Earthsea", "Ursula K. Le Guin", "Earthsea", 1f),
            book("earthsea-2", "The Tombs of Atuan", "Ursula K. Le Guin", "Earthsea", 2f),
            book("left-hand", "The Left Hand of Darkness", "Ursula K. Le Guin"),
            book("singleton", "Piranesi", "Susanna Clarke"),
        )

        val proposals = AiCollectionCurator.structuralProposals(books)

        assertEquals(setOf("Earthsea", "Ursula K. Le Guin"), proposals.map { it.name }.toSet())
        assertEquals(
            listOf("earthsea-1", "earthsea-2"),
            proposals.first { it.kind == AiCollectionKind.SERIES }.bookUuids,
        )
        assertEquals(
            setOf("earthsea-1", "earthsea-2", "left-hand"),
            proposals.first { it.kind == AiCollectionKind.AUTHOR }.bookUuids.toSet(),
        )
    }

    @Test
    fun structuralProposalsAvoidSingletonAndPlaceholderAuthorClutter() {
        val proposals = AiCollectionCurator.structuralProposals(
            listOf(
                book("one", "Book One", "Unknown author"),
                book("two", "Book Two", "Unknown author"),
                book("three", "Only Book", "A Real Author"),
            ),
        )

        assertTrue(proposals.isEmpty())
    }

    @Test
    fun acceptedProposalsRequireStrongSpecificThemes() {
        val books = listOf(
            book("one", "One", "Author A"),
            book("two", "Two", "Author B"),
            book("three", "Three", "Author C"),
        )
        val proposals = listOf(
            theme("Fiction", 0.99f),
            theme("Quietly Strange Worlds", 0.89f),
            theme("Uncanny Rooms", 0.94f),
        )

        val accepted = AiCollectionCurator.acceptedProposals(books, proposals)

        assertFalse(accepted.any { it.name == "Fiction" })
        assertFalse(accepted.any { it.name == "Quietly Strange Worlds" })
        assertTrue(accepted.any { it.name == "Uncanny Rooms" })
    }

    @Test
    fun inferredSeriesNeedExceptionalConfidence() {
        val books = listOf(
            book("one", "The Journey: Book One", "Author A"),
            book("two", "The Journey: Book Two", "Author A"),
        )
        val uncertain = AiCollectionProposal(
            name = "The Journey",
            kind = AiCollectionKind.SERIES,
            bookUuids = books.map { it.id },
            confidence = 0.94f,
            explanation = "Similar titles.",
        )
        val confident = uncertain.copy(confidence = 0.96f)

        assertFalse(AiCollectionCurator.acceptedProposals(books, listOf(uncertain)).any { it.name == "The Journey" })
        assertTrue(AiCollectionCurator.acceptedProposals(books, listOf(confident)).any { it.name == "The Journey" })
    }

    @Test
    fun inferredSeriesNeverOverrideConflictingMetadata() {
        val books = listOf(
            book("one", "Book One", "Author A", "First Series", 1f),
            book("two", "Book Two", "Author A", "Second Series", 1f),
        )
        val proposal = AiCollectionProposal(
            name = "A Mistaken Series",
            kind = AiCollectionKind.SERIES,
            bookUuids = books.map { it.id },
            confidence = 0.99f,
            explanation = "Similar titles.",
        )

        assertFalse(
            AiCollectionCurator.acceptedProposals(books, listOf(proposal))
                .any { it.name == "A Mistaken Series" },
        )
    }

    @Test
    fun authorAliasesCollapseIntoCanonicalAuthorShelf() {
        val books = listOf(
            book("one", "One", "J. Kenner"),
            book("two", "Two", "J. Kenner"),
        )
        val remote = AiCollectionProposal(
            name = "Books by J. Kenner",
            kind = AiCollectionKind.AUTHOR,
            bookUuids = books.map { it.id },
            confidence = 0.99f,
            explanation = "The same author.",
        )

        val accepted = AiCollectionCurator.acceptedProposals(books, listOf(remote))

        assertEquals(1, accepted.count { it.kind == AiCollectionKind.AUTHOR })
        assertEquals("J. Kenner", accepted.first { it.kind == AiCollectionKind.AUTHOR }.name)
        assertTrue(AiCollectionCurator.matchesCollectionIdentity(accepted.first(), "Books by J. Kenner"))
    }

    @Test
    fun seriesAndAuthorVariantsCompleteTheShelf() {
        val books = listOf(
            book(
                "one",
                "Insatiable (The Edge of Darkness: Book 1)",
                "Leigh Rivers",
                "The Edge of Darkness",
                1f,
            ),
            book(
                "two",
                "Voracious (The Edge of Darkness: Book 2)",
                "Rivers, Leigh",
                "The Edge of Darkness Trilogy",
                2f,
            ),
            book(
                "three",
                "Restitution (The Edge of Darkness: Book 3)",
                "Leigh Rivers",
                "The Edge of Darkness Trilogy",
                3f,
            ),
        )

        val accepted = AiCollectionCurator.acceptedProposals(books, emptyList())

        assertEquals(
            setOf("one", "two", "three"),
            accepted.first { it.kind == AiCollectionKind.SERIES }.bookUuids.toSet(),
        )
        assertEquals(
            setOf("one", "two", "three"),
            accepted.first { it.kind == AiCollectionKind.AUTHOR }.bookUuids.toSet(),
        )
    }

    private fun theme(name: String, confidence: Float) = AiCollectionProposal(
        name = name,
        kind = AiCollectionKind.THEME,
        bookUuids = listOf("one", "two", "three"),
        confidence = confidence,
        explanation = "Shared setting and mood.",
    )

    private fun book(
        id: String,
        title: String,
        author: String,
        seriesName: String? = null,
        seriesIndex: Float? = null,
    ) = AiCurationBook(
        id = id,
        title = title,
        author = author,
        category = "Fiction",
        genres = listOf("Literary"),
        seriesName = seriesName,
        seriesIndex = seriesIndex,
    )
}
