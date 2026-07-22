package app.vellum.reader.reader.ui

import app.vellum.reader.reader.layout.ReaderPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNarrationPagingTest {
    private val pages = listOf(
        ReaderPage(emptyList(), startChar = 0, endChar = 100),
        ReaderPage(emptyList(), startChar = 100, endChar = 200),
        ReaderPage(emptyList(), startChar = 200, endChar = 300),
        ReaderPage(emptyList(), startChar = 300, endChar = 400),
    )

    @Test
    fun narrationDoesNotTurnWhenEnteringSecondVisiblePageOfSpread() {
        assertFalse(narrationHasLeftVisiblePages(pages, 0, 2, 100))
        assertFalse(narrationHasLeftVisiblePages(pages, 0, 2, 199))
    }

    @Test
    fun narrationTurnsAfterLeavingVisibleSpread() {
        assertTrue(narrationHasLeftVisiblePages(pages, 0, 2, 200))
        assertTrue(narrationHasLeftVisiblePages(pages, 2, 2, 400))
    }

    @Test
    fun singlePageModeStillTurnsAtNextPageBoundary() {
        assertFalse(narrationHasLeftVisiblePages(pages, 0, 1, 99))
        assertTrue(narrationHasLeftVisiblePages(pages, 0, 1, 100))
    }
}
