package kapoue.hestia.data.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `une sauvegarde v1 sans isLight reste lisible et vaut faux`() {
        val old = """{"name":"Prise","ipAddress":"192.168.1.96","switchId":0,"type":"PLUG","driver":"SHELLY_GEN2"}"""
        assertFalse(json.decodeFromString(DeviceBackup.serializer(), old).isLight)
    }

    @Test
    fun `isLight est relu quand present`() {
        val withLight = """{"name":"Variateur","ipAddress":"192.168.1.50","switchId":0,"type":"LAMP","driver":"SHELLY_GEN2","isLight":true}"""
        assertTrue(json.decodeFromString(DeviceBackup.serializer(), withLight).isLight)
    }
}
