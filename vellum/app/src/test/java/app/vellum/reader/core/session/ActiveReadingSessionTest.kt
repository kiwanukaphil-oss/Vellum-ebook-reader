package app.vellum.reader.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveReadingSessionTest {
    @Test
    fun countsOnlyActiveIntervals() {
        var wall = 1_000L
        var monotonic = 0L
        val session = ActiveReadingSession({ wall }, { monotonic })

        session.setActive(true)
        monotonic += 20_000L
        session.setActive(false)
        monotonic += 90_000L
        session.setActive(true)
        monotonic += 15_000L
        wall += 125_000L

        val result = session.finish("book", pagesTurned = 4)

        requireNotNull(result)
        assertEquals(35_000L, result.msRead)
        assertEquals(4, result.pagesTurned)
        assertEquals(1_000L, result.startedAt)
        assertEquals(126_000L, result.endedAt)
        assertTrue(result.uuid.isNotBlank())
    }

    @Test
    fun ignoresDuplicateLifecycleSignalsAndShortSessions() {
        var monotonic = 0L
        val session = ActiveReadingSession({ 1_000L }, { monotonic })

        session.setActive(true)
        session.setActive(true)
        monotonic = 29_999L

        assertNull(session.finish("book", pagesTurned = 1))
    }
}
