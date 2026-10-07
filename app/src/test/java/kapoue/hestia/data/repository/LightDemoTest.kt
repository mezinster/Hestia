package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
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
}
