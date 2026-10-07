package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.model.LightStatusResult
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightDemoTest {

    @Test
    fun `le variateur demo est allume a 40 pourcent`() {
        val demo = Device(name = "Bedroom Dimmer", ipAddress = "203.0.113.7", type = DeviceType.LAMP, isLight = true, supportsSwitch = false)
        val status = demoLightStatus(demo)
        assertTrue(status.output)
        assertEquals(40.0, status.brightness!!, 0.0)
    }

    @Test
    fun `une commande demo met a jour l'etat`() {
        val start = LightStatusResult(id = 0, output = true, brightness = 40.0, apower = 6.2)
        val off = applyDemoLightSet(start, on = false, brightness = null)
        assertEquals(false, off.output)
        assertEquals(40.0, off.brightness!!, 0.0)
        val bright = applyDemoLightSet(off, on = true, brightness = 80)
        assertTrue(bright.output)
        assertEquals(80.0, bright.brightness!!, 0.0)
    }
}
