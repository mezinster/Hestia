package kapoue.hestia.data.backup

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupNotifyMaskTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `backup without notifyMask restores ALL`() {
        val old = """{"name":"P","ipAddress":"1.2.3.4","switchId":0,"type":"PLUG","driver":"SHELLY_GEN2"}"""
        val entity = json.decodeFromString(DeviceBackup.serializer(), old).toEntity()
        assertEquals(63, entity.notifyMask)
    }

    @Test
    fun `round-trip keeps a custom notifyMask`() {
        val device = Device(name = "P", ipAddress = "1.2.3.4", type = DeviceType.PLUG, notifyMask = 5)
        val text = json.encodeToString(DeviceBackup.serializer(), device.toBackup())
        val back = json.decodeFromString(DeviceBackup.serializer(), text).toEntity()
        assertEquals(5, back.notifyMask)
    }
}
