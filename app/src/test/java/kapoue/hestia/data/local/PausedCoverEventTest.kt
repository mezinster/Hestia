package kapoue.hestia.data.local

import java.time.LocalDate
import kapoue.hestia.data.local.entity.toEvent
import kapoue.hestia.data.local.entity.toPaused
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PausedCoverEventTest {

    @Test
    fun `ouverture hebdomadaire fait l'aller-retour`() {
        val event = CoverEvent(7, 30, setOf(3, 1, 2), null, CoverEventAction.Open, jobId = 4)
        val paused = event.toPaused(deviceId = 9, pausedAt = 1234L)
        assertEquals("1,2,3", paused.days)
        assertEquals("open", paused.action)
        assertNull(paused.position)
        assertEquals(9L, paused.deviceId)
        assertEquals(1234L, paused.pausedAt)
        assertEquals(event.copy(jobId = null), paused.toEvent())
    }

    @Test
    fun `fermeture tous les jours fait l'aller-retour`() {
        val event = CoverEvent(21, 0, emptySet(), null, CoverEventAction.Close)
        val paused = event.toPaused(1, 0L)
        assertEquals("", paused.days)
        assertNull(paused.date)
        assertEquals("close", paused.action)
        assertEquals(event, paused.toEvent())
    }

    @Test
    fun `position unique datee fait l'aller-retour`() {
        val event = CoverEvent(12, 5, emptySet(), LocalDate.of(2026, 11, 3), CoverEventAction.GoTo(50), jobId = 2)
        val paused = event.toPaused(1, 0L)
        assertEquals("2026-11-03", paused.date)
        assertEquals("goto", paused.action)
        assertEquals(50, paused.position)
        val back = paused.toEvent()
        assertEquals(event.copy(jobId = null), back)
        assertNull(back.jobId)
    }
}
