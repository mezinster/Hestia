package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverDemoTest {
    private fun dev(suffix: Int) = Device(name = "S", ipAddress = "203.0.113.$suffix", type = DeviceType.SHUTTER, isCover = true, supportsSwitch = false)

    @Test
    fun `volets demo calibre et non calibre`() {
        val calibrated = demoCoverStatus(dev(12))
        assertTrue(calibrated.posControl); assertEquals(60, calibrated.currentPos)
        val raw = demoCoverStatus(dev(13))
        assertFalse(raw.posControl); assertNull(raw.currentPos)
    }

    @Test
    fun `mouvement simule a 10 pour cent par seconde puis arret a la cible`() {
        val base = demoCoverStatus(dev(12))
        val move = DemoCoverMove(from = 60, target = 0, startedAtMs = 0)
        val mid = demoCoverAt(base, move, nowMs = 3_000)
        assertEquals("closing", mid.state); assertEquals(30, mid.currentPos)
        val end = demoCoverAt(base, move, nowMs = 10_000)
        assertEquals("closed", end.state); assertEquals(0, end.currentPos)
        val partial = demoCoverAt(base, DemoCoverMove(from = 60, target = 80, startedAtMs = 0), nowMs = 5_000)
        assertEquals("stopped", partial.state); assertEquals(80, partial.currentPos)
    }
}
