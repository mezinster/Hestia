package kapoue.hestia.data.repository

import java.time.LocalDate
import java.time.LocalDateTime
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Règles de validation avant création d'événements de volet (lot S2, 2026-10-07). */
class CoverScheduleRulesTest {
    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)

    private fun ev(h: Int, m: Int = 0, days: Set<Int> = emptySet(), date: LocalDate? = null) =
        CoverEvent(h, m, days, date, CoverEventAction.Open, jobId = 1)

    @Test
    fun `un unique dans le passe est refuse`() {
        val past = ev(8, date = LocalDate.of(2026, 10, 7))
        assertEquals(CoverEventResult.PastOnce, validateNewEvents(emptyList(), listOf(past), now))
    }

    @Test
    fun `un unique dans le futur est accepte`() {
        assertNull(validateNewEvents(emptyList(), listOf(ev(18, date = LocalDate.of(2026, 10, 7))), now))
    }

    @Test
    fun `meme creneau qu'un existant est un doublon`() {
        assertEquals(CoverEventResult.Duplicate, validateNewEvents(listOf(ev(7)), listOf(ev(7)), now))
    }

    @Test
    fun `deux nouveaux sur le meme creneau sont un doublon`() {
        assertEquals(CoverEventResult.Duplicate, validateNewEvents(emptyList(), listOf(ev(7), ev(7)), now))
    }

    @Test
    fun `neuf existants plus une plage depassent la limite`() {
        val existing = (0..8).map { ev(it) }
        assertEquals(CoverEventResult.LimitReached, validateNewEvents(existing, listOf(ev(10), ev(11)), now))
    }

    @Test
    fun `huit existants plus une plage tiennent`() {
        val existing = (0..7).map { ev(it) }
        assertNull(validateNewEvents(existing, listOf(ev(10), ev(11)), now))
    }

    @Test
    fun `modifier un evenement sur son propre creneau est accepte`() {
        val old = ev(7)
        assertNull(validateNewEvents(listOf(old), listOf(ev(7)), now, ignoring = old))
    }

    @Test
    fun `modifier ne compte pas l'ancien dans la limite`() {
        val existing = (0..9).map { ev(it) }
        assertNull(validateNewEvents(existing, listOf(ev(20)), now, ignoring = existing[0]))
    }
}
