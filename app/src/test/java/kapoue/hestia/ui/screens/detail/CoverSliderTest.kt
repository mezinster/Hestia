package kapoue.hestia.ui.screens.detail

import kapoue.hestia.ui.screens.dashboard.CoverMotion
import kapoue.hestia.ui.screens.dashboard.CoverStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverSliderTest {

    private fun online(position: Int?) = CoverStatus.Online(
        motion = CoverMotion.STOPPED,
        position = position,
        positionControl = position != null,
        powerWatts = null,
        faults = emptyList(),
    )

    @Test
    fun `relacher le curseur envoie la position arrondie`() {
        assertEquals(37, coverSliderTarget(37.4f))
    }

    @Test
    fun `la position est bornee entre 0 et 100`() {
        assertEquals(0, coverSliderTarget(-5f))
        assertEquals(100, coverSliderTarget(140f))
    }

    @Test
    fun `pas de recalage pendant un glisse`() {
        assertNull(coverResyncTarget(online(40), dragging = true))
    }

    @Test
    fun `recalage sur la position lue hors glisse`() {
        assertEquals(40f, coverResyncTarget(online(40), dragging = false))
    }

    @Test
    fun `pas de recalage sans position connue ou hors ligne`() {
        assertNull(coverResyncTarget(online(null), dragging = false))
        assertNull(coverResyncTarget(CoverStatus.Offline, dragging = false))
        assertNull(coverResyncTarget(CoverStatus.Loading, dragging = false))
    }
}
