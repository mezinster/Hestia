package kapoue.hestia.ui.screens.dashboard

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
