package kapoue.hestia.data.rpc

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType
import kotlinx.serialization.json.put
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

    private fun job(id: Int, method: String, channel: Int, on: Boolean) = kapoue.hestia.data.rpc.model.ScheduleJob(
        id = id,
        timespec = "0 0 18 * * *",
        calls = listOf(
            kapoue.hestia.data.rpc.model.ScheduleCall(
                method = method,
                params = kotlinx.serialization.json.buildJsonObject { put("id", channel); put("on", on) },
            ),
        ),
    )

    @Test
    fun `relais et variateur de meme id ne se melangent pas`() {
        val jobs = listOf(job(1, "Switch.Set", 0, true), job(2, "Light.Set", 0, true), job(3, "Light.Set", 1, false))
        assertEquals(listOf(1), jobs.filter { it.actionFor(ChannelControl.SWITCH, 0) != null }.map { it.id })
        assertEquals(listOf(2), jobs.filter { it.actionFor(ChannelControl.LIGHT, 0) != null }.map { it.id })
        assertEquals(listOf(3), jobs.filter { it.actionFor(ChannelControl.LIGHT, 1) != null }.map { it.id })
    }

    @Test
    fun `une tache sans action de canal est ignoree`() {
        val other = kapoue.hestia.data.rpc.model.ScheduleJob(id = 9, calls = listOf(kapoue.hestia.data.rpc.model.ScheduleCall(method = "Script.Start")))
        assertEquals(null, other.actionFor(ChannelControl.SWITCH, 0))
    }

    private val light = Device(name = "L", ipAddress = "1.2.3.4", type = DeviceType.LAMP, isLight = true, supportsSwitch = false)
    private val plug = Device(name = "P", ipAddress = "1.2.3.4", type = DeviceType.PLUG)

    @Test
    fun `un variateur refuse presence et coupure`() {
        assertEquals(true, planningRequestAllowed(light, cutoffThresholdW = null, marginMinutes = null))
        assertEquals(false, planningRequestAllowed(light, cutoffThresholdW = 100, marginMinutes = null))
        assertEquals(false, planningRequestAllowed(light, cutoffThresholdW = null, marginMinutes = 30))
    }

    @Test
    fun `un relais accepte tout comme avant`() {
        assertEquals(true, planningRequestAllowed(plug, cutoffThresholdW = 100, marginMinutes = null))
        assertEquals(true, planningRequestAllowed(plug, cutoffThresholdW = null, marginMinutes = 30))
    }
}
