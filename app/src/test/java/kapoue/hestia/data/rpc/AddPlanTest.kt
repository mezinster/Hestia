package kapoue.hestia.data.rpc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddPlanTest {

    private fun caps(switches: List<Int>, lights: List<Int>) = DeviceCapabilities(
        generation = 3, model = null, reportedName = null,
        switchChannels = switches, lightChannels = lights,
        hasScripting = true, hasPowerMetering = false,
    )

    @Test
    fun `un variateur seul passe par l'ajout light`() = assertTrue(caps(emptyList(), listOf(0)).isLightOnly())

    @Test
    fun `un relais n'est pas light`() = assertFalse(caps(listOf(0), emptyList()).isLightOnly())

    @Test
    fun `un appareil mixte garde le parcours relais puis ajoute ses lights`() = assertFalse(caps(listOf(0), listOf(1)).isLightOnly())
}
