package app.vellum.reader.search

import app.vellum.reader.core.data.ChapterSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFunctionsTest {
    @Test
    fun escapesSqlLikeWildcardsAndEscapeCharacter() {
        assertEquals("50\\% \\_done\\\\now", escapeLike("50% _done\\now"))
    }

    @Test
    fun returnsEveryOccurrenceWithExactOffsetsAndMarkers() {
        val results = expandPassageHits(
            chapters = listOf(
                ChapterSearchHit(
                    bookUuid = "book",
                    bookTitle = "Title",
                    chapterIndex = "3",
                    body = "Calm opening. A calm middle. CALM ending.",
                ),
            ),
            term = "calm",
        )

        assertEquals(listOf(0, 16, 29), results.map { it.charOffset })
        assertTrue(results.all { it.chapterIndex == 3 })
        assertTrue(results.all { "⟪" in it.snippet && "⟫" in it.snippet })
    }

    @Test
    fun capsResultsToProtectTheUi() {
        val text = List(150) { "match" }.joinToString(" ")
        val results = expandPassageHits(
            listOf(ChapterSearchHit("book", "Title", "0", text)),
            "match",
        )

        assertEquals(100, results.size)
    }

    @Test
    fun ranksChaptersWithMoreOccurrencesFirst() {
        val results = expandPassageHits(
            listOf(
                ChapterSearchHit("book-a", "A", "0", "one match"),
                ChapterSearchHit("book-b", "B", "1", "match, match, match"),
            ),
            "match",
        )

        assertEquals("book-b", results.first().bookUuid)
    }
}
