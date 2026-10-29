package kapoue.hestia.ui.screens.detail

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceRepository.SmokeCutoffTarget
import kapoue.hestia.data.repository.SmokeTargetBlock
import kapoue.hestia.data.repository.SmokeTargetGate
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeCutoffRowsTest {
    private fun device(ip: String, pin: String? = null) =
        Device(name = "d", ipAddress = ip, switchId = 0, type = DeviceType.PLUG, certPin = pin)

    private val open = device("192.168.1.50")
    private val locked = device("192.168.1.51")

    @Test fun `open candidate is enabled and not to review`() {
        val row = cutoffRows(listOf(open), emptyList(), SmokeTargetGate.OPEN).single()
        assertTrue(row.enabled)
        assertFalse(row.toReview)
        assertEquals(null, row.block)
    }

    @Test fun `protected unchecked candidate is disabled`() {
        val gate = SmokeTargetGate(listOf(locked), emptyMap(), setOf(locked.ipAddress))
        val row = cutoffRows(listOf(locked), emptyList(), gate).single()
        assertFalse(row.enabled)
        assertEquals(SmokeTargetBlock.AUTH, row.block)
    }

    @Test fun `configured blocked target is listed as to review`() {
        val gate = SmokeTargetGate(listOf(locked), emptyMap(), setOf(locked.ipAddress))
        val targets = listOf(SmokeCutoffTarget(locked.ipAddress, 0))
        val row = cutoffRows(listOf(locked), targets, gate).single()
        assertTrue(row.checked)
        assertTrue(row.enabled)
        assertTrue(row.toReview)
    }

    @Test fun `manual target equal to a protected device is refused`() {
        val pinned = device("192.168.1.51", pin = "abc")
        val gate = SmokeTargetGate(listOf(pinned), emptyMap(), emptySet())
        assertEquals(SmokeTargetBlock.HTTPS, manualTargetBlock(" 192.168.1.51 ", gate))
    }
}
