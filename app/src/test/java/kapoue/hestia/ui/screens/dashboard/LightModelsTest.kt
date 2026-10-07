package kapoue.hestia.ui.screens.dashboard

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightModelsTest {

    @Test
    fun `un statut lu devient Online avec luminosite arrondie`() {
        val status = RpcResult.Success(LightStatusResult(id = 0, output = true, brightness = 39.6, apower = 5.0)).toLightStatus()
        assertEquals(LightStatus.Online(on = true, brightness = 40, powerWatts = 5.0), status)
    }

    @Test
    fun `une luminosite absente vaut 100`() {
        val status = RpcResult.Success(LightStatusResult(id = 0, output = false)).toLightStatus()
        assertEquals(LightStatus.Online(on = false, brightness = 100, powerWatts = null), status)
    }

    @Test
    fun `un echec devient Offline`() {
        assertEquals(LightStatus.Offline, RpcResult.Failure(RpcFailure.TIMEOUT).toLightStatus())
    }

    @Test
    fun `un canal light n'est jamais regroupe en bandeau`() {
        assertFalse(Device(name = "L", ipAddress = "1.2.3.4", type = DeviceType.LAMP, isLight = true).isGroupable())
        assertTrue(Device(name = "P", ipAddress = "1.2.3.4", type = DeviceType.PLUG).isGroupable())
    }

    private fun plug(id: Int, ip: String = "1.2.3.4") = Device(name = "P$id", ipAddress = ip, switchId = id, type = DeviceType.PLUG)
    private fun light(ip: String = "1.2.3.4") = Device(name = "L", ipAddress = ip, switchId = 0, type = DeviceType.LAMP, isLight = true)

    @Test
    fun `appareil mixte avec le light en premier garde le bandeau des relais`() {
        val flags = computeGroupFlags(listOf(light(), plug(0), plug(1)))
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = false), flags[0])
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = true), flags[1])
        assertEquals(GroupFlags(isFirstInGroup = false, isMultiChannel = true), flags[2])
    }

    @Test
    fun `appareil mixte avec le light en dernier garde le bandeau des relais`() {
        val flags = computeGroupFlags(listOf(plug(0), plug(1), light()))
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = true), flags[0])
        assertEquals(GroupFlags(isFirstInGroup = false, isMultiChannel = true), flags[1])
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = false), flags[2])
    }

    @Test
    fun `un relais seul avec un light reste une tuile simple`() {
        val flags = computeGroupFlags(listOf(plug(0), light()))
        assertFalse(flags[0].isMultiChannel)
        assertFalse(flags[1].isMultiChannel)
    }

    @Test
    fun `fin du minuteur dans le referentiel elapsed`() {
        // Démarré il y a 100 s pour 600 s : il reste 500 s.
        assertEquals(50_000L + 500_000L, lightTimerEndsAt(startedAt = 1000.0, duration = 600.0, nowEpochSec = 1100.0, nowElapsedMs = 50_000L))
    }

    @Test
    fun `minuteur termine ou absent donne null`() {
        assertNull(lightTimerEndsAt(startedAt = 1000.0, duration = 60.0, nowEpochSec = 1100.0, nowElapsedMs = 0L))
        assertNull(lightTimerEndsAt(startedAt = null, duration = 60.0, nowEpochSec = 1100.0, nowElapsedMs = 0L))
        assertNull(lightTimerEndsAt(startedAt = 1000.0, duration = null, nowEpochSec = 1100.0, nowElapsedMs = 0L))
    }
}
