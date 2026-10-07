package kapoue.hestia.data.repository

import kapoue.hestia.data.rpc.DeviceCapabilities
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Construction d'un canal volet (lot S1, 2026-10-07). */
class CoverRepositoryTest {

    @Test
    fun `un canal volet n'est jamais un relais ni un variateur`() {
        val caps = DeviceCapabilities(generation = 2, model = "S2PM", reportedName = null, switchChannels = emptyList(), coverChannels = listOf(0), hasScripting = true, hasPowerMetering = true)
        val d = buildCoverDevice("Volet", "Volet", "192.168.1.60", coverId = 0, capabilities = caps, position = 3)
        assertTrue(d.isCover); assertFalse(d.isLight); assertFalse(d.supportsSwitch)
        assertEquals(DeviceType.SHUTTER, d.type); assertEquals(0, d.switchId); assertEquals(3, d.position)
    }
}
