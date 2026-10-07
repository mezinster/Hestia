package kapoue.hestia.data.rpc

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Action d'un canal dans un planning (lot C3, 2026-10-07). */
class ChannelControlTest {

    @Test
    fun `un relais est pilote par Switch Set`() =
        assertEquals(ChannelControl.SWITCH, channelControlFor(Device(name = "P", ipAddress = "1.2.3.4", type = DeviceType.PLUG)))

    @Test
    fun `un variateur est pilote par Light Set`() = assertEquals(
        ChannelControl.LIGHT,
        channelControlFor(Device(name = "L", ipAddress = "1.2.3.4", type = DeviceType.LAMP, isLight = true, supportsSwitch = false)),
    )

    @Test
    fun `l'appel relais reste identique a l'existant`() = assertEquals(
        """{"method":"Switch.Set","params":{"id":0,"on":true}}""",
        scheduleActionCall(ChannelControl.SWITCH, 0, on = true).toString(),
    )

    @Test
    fun `l'appel variateur n'envoie jamais de luminosite`() = assertEquals(
        """{"method":"Light.Set","params":{"id":2,"on":false}}""",
        scheduleActionCall(ChannelControl.LIGHT, 2, on = false).toString(),
    )
}
