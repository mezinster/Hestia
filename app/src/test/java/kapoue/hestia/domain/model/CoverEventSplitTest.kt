package kapoue.hestia.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverEventSplitTest {
    private val now = LocalDateTime.of(2026, 10, 7, 10, 0)
    private val expired = CoverEvent(9, 0, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Open, jobId = 1)
    private val future = CoverEvent(21, 0, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Close, jobId = 2)
    private val weekly = CoverEvent(7, 0, action = CoverEventAction.Open, jobId = 3)

    @Test
    fun `lecture normale purge les uniques echus`() {
        val (returned, purge) = splitForRead(listOf(expired, future, weekly), now, keepExpired = false)
        assertEquals(listOf(future, weekly), returned)
        assertEquals(listOf(expired), purge)
    }

    @Test
    fun `unique echu depuis moins de 30 min ni renvoye ni purge`() {
        val recent = CoverEvent(9, 45, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Open, jobId = 4)
        val (returned, purge) = splitForRead(listOf(recent, weekly), now, keepExpired = false)
        assertEquals(listOf(weekly), returned)
        assertEquals(emptyList<CoverEvent>(), purge)
    }

    @Test
    fun `unique echu depuis exactement 30 min pas encore purge, au-dela purge`() {
        val edge = CoverEvent(9, 30, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Open, jobId = 4)
        assertEquals(emptyList<CoverEvent>(), splitForRead(listOf(edge), now, keepExpired = false).second)
        val older = CoverEvent(9, 29, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Open, jobId = 5)
        assertEquals(listOf(older), splitForRead(listOf(older), now, keepExpired = false).second)
    }

    @Test
    fun `lecture du worker garde aussi un echu recent`() {
        val recent = CoverEvent(9, 45, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Open, jobId = 4)
        assertEquals(listOf(recent), splitForRead(listOf(recent), now, keepExpired = true).first)
    }

    @Test
    fun `lecture du worker garde les echus et ne purge rien`() {
        val (returned, purge) = splitForRead(listOf(expired, future, weekly), now, keepExpired = true)
        assertEquals(listOf(expired, future, weekly), returned)
        assertEquals(emptyList<CoverEvent>(), purge)
    }

    @Test
    fun `unique de 10h00 dans la fenetre 9h55-10h05`() {
        val ev = CoverEvent(10, 0, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Open)
        assertEquals(
            listOf(LocalDateTime.of(2026, 10, 7, 10, 0)),
            coverEventInstantsBetween(ev, LocalDateTime.of(2026, 10, 7, 9, 55), LocalDateTime.of(2026, 10, 7, 10, 5)),
        )
    }
}
