package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.LightStatusResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decode l'exemple Light GetStatus de la doc`() {
        val body = """{"id":0,"source":"timer","output":false,"brightness":50,"timer_duration":60,"temperature":{"tC":41.2}}"""
        val status = json.decodeFromString(LightStatusResult.serializer(), body)
        assertEquals(0, status.id)
        assertEquals(false, status.output)
        assertEquals(50.0, status.brightness!!, 0.0)
        assertEquals("timer", status.source)
        assertNull(status.apower)
    }

    @Test
    fun `decode la puissance quand l'appareil la mesure`() {
        val status = json.decodeFromString(LightStatusResult.serializer(), """{"id":1,"output":true,"brightness":12.5,"apower":7.4}""")
        assertEquals(7.4, status.apower!!, 0.0)
    }
}
