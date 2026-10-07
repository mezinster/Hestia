package kapoue.hestia.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverEventInstantsTest {
    // Mardi 2026-10-06 et mercredi 2026-10-07.
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, day, h, m)
    private val open = CoverEventAction.Open

    @Test
    fun `hebdomadaire sur une fenetre de 2 jours`() {
        val ev = CoverEvent(7, 30, setOf(2, 3), action = open) // mardi, mercredi
        assertEquals(listOf(at(6, 7, 30), at(7, 7, 30)), coverEventInstantsBetween(ev, at(5, 22), at(7, 12)))
        val thuOnly = CoverEvent(7, 30, setOf(4), action = open)
        assertEquals(emptyList<LocalDateTime>(), coverEventInstantsBetween(thuOnly, at(5, 22), at(7, 12)))
    }

    @Test
    fun `tous les jours sur 3 jours`() {
        val ev = CoverEvent(21, 0, action = CoverEventAction.Close)
        assertEquals(listOf(at(6, 21), at(7, 21), at(8, 21)), coverEventInstantsBetween(ev, at(5, 22), at(8, 22)))
    }

    @Test
    fun `unique dans et hors fenetre`() {
        val ev = CoverEvent(9, 0, date = LocalDate.of(2026, 10, 7), action = open)
        assertEquals(listOf(at(7, 9)), coverEventInstantsBetween(ev, at(7, 8), at(7, 10)))
        assertEquals(emptyList<LocalDateTime>(), coverEventInstantsBetween(ev, at(7, 9, 1), at(7, 10)))
        assertEquals(emptyList<LocalDateTime>(), coverEventInstantsBetween(ev, at(6, 0), at(7, 8)))
    }

    @Test
    fun `bornes from exclu to inclus`() {
        val ev = CoverEvent(9, 0, action = open)
        assertEquals(emptyList<LocalDateTime>(), coverEventInstantsBetween(ev, at(7, 9), at(7, 9, 30)))
        assertEquals(listOf(at(7, 9)), coverEventInstantsBetween(ev, at(7, 8), at(7, 9)))
    }
}
