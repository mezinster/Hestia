package kapoue.hestia.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverEventTest {
    // Mercredi 2026-10-07 10:00.
    private val now = LocalDateTime.of(2026, 10, 7, 10, 0)

    @Test
    fun `prochaine occurrence hebdomadaire plus tard aujourd'hui, demain, semaine suivante`() {
        val wed = 3; val thu = 4
        assertEquals(LocalDateTime.of(2026, 10, 7, 21, 0), CoverEvent(21, 0, setOf(wed), action = CoverEventAction.Close).nextOccurrence(now))
        assertEquals(LocalDateTime.of(2026, 10, 8, 7, 30), CoverEvent(7, 30, setOf(thu), action = CoverEventAction.Open).nextOccurrence(now))
        assertEquals(LocalDateTime.of(2026, 10, 14, 7, 30), CoverEvent(7, 30, setOf(wed), action = CoverEventAction.Open).nextOccurrence(now))
        assertEquals(LocalDateTime.of(2026, 10, 8, 7, 30), CoverEvent(7, 30, action = CoverEventAction.Open).nextOccurrence(now))
    }

    @Test
    fun `evenement unique futur ou passe`() {
        val future = CoverEvent(13, 0, date = LocalDate.of(2026, 12, 24), action = CoverEventAction.GoTo(50))
        assertEquals(LocalDateTime.of(2026, 12, 24, 13, 0), future.nextOccurrence(now)); assertFalse(future.isExpiredOnce(now))
        val past = CoverEvent(9, 0, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Open)
        assertNull(past.nextOccurrence(now)); assertTrue(past.isExpiredOnce(now))
    }

    @Test
    fun `plage normale et inversee`() {
        val (a, b) = windowEvents(7, 30, 21, 0, setOf(1, 2, 3, 4, 5), null, inverted = false)
        assertEquals(CoverEvent(7, 30, setOf(1, 2, 3, 4, 5), action = CoverEventAction.Open), a)
        assertEquals(CoverEvent(21, 0, setOf(1, 2, 3, 4, 5), action = CoverEventAction.Close), b)
        val (c, d) = windowEvents(7, 30, 21, 0, emptySet(), null, inverted = true)
        assertEquals(CoverEventAction.Close, c.action); assertEquals(21, c.hour)
        assertEquals(CoverEventAction.Open, d.action); assertEquals(7, d.hour)
    }

    @Test
    fun `meme creneau`() {
        assertTrue(CoverEvent(7, 0, setOf(1), action = CoverEventAction.Open).sameSlotAs(CoverEvent(7, 0, setOf(1), action = CoverEventAction.Close)))
        assertFalse(CoverEvent(7, 0, setOf(1), action = CoverEventAction.Open).sameSlotAs(CoverEvent(7, 0, setOf(2), action = CoverEventAction.Open)))
    }
}
