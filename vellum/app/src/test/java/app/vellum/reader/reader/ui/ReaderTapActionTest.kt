package app.vellum.reader.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTapActionTest {
    @Test
    fun broadMiddleIncludingBothPageCentresOpensControls() {
        val spreadWidth = 1_000f

        assertEquals(
            ReaderTapAction.TOGGLE_CONTROLS,
            readerTapAction(x = 250f, width = spreadWidth, controlsVisible = false),
        )
        assertEquals(
            ReaderTapAction.TOGGLE_CONTROLS,
            readerTapAction(x = 750f, width = spreadWidth, controlsVisible = false),
        )
    }

    @Test
    fun onlyOuterEdgesTurnPagesWhenControlsAreHidden() {
        assertEquals(
            ReaderTapAction.PREVIOUS_PAGE,
            readerTapAction(x = 100f, width = 1_000f, controlsVisible = false),
        )
        assertEquals(
            ReaderTapAction.NEXT_PAGE,
            readerTapAction(x = 900f, width = 1_000f, controlsVisible = false),
        )
    }

    @Test
    fun visibleControlsTakePriorityOverPageTurns() {
        assertEquals(
            ReaderTapAction.TOGGLE_CONTROLS,
            readerTapAction(x = 10f, width = 1_000f, controlsVisible = true),
        )
        assertEquals(
            ReaderTapAction.TOGGLE_CONTROLS,
            readerTapAction(x = 990f, width = 1_000f, controlsVisible = true),
        )
    }
}
