package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.LightConfigResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Paramètres envoyés au composant Light et décodage des champs minuteur / nom (C1, 2026-10-07). */
class LightRpcParamsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `allumer avec minuteur envoie toggle_after`() {
        assertEquals("""{"id":1,"on":true,"toggle_after":1800}""", lightSetParams(1, on = true, brightness = null, toggleAfterSec = 1800).toString())
    }

    @Test
    fun `sans minuteur toggle_after est absent`() {
        assertEquals("""{"id":0,"brightness":40}""", lightSetParams(0, on = null, brightness = 40, toggleAfterSec = null).toString())
    }

    @Test
    fun `le nom est envoye dans config`() {
        assertEquals("""{"id":2,"config":{"name":"Salon"}}""", lightSetConfigNameParams(2, "Salon").toString())
    }

    @Test
    fun `decode les champs du minuteur de Light GetStatus`() {
        val status = json.decodeFromString(
            LightStatusResult.serializer(),
            """{"id":0,"output":true,"brightness":50,"timer_started_at":1700000000.5,"timer_duration":600}""",
        )
        assertEquals(1700000000.5, status.timerStartedAt!!, 0.0)
        assertEquals(600.0, status.timerDuration!!, 0.0)
    }

    @Test
    fun `decode le nom de Light GetConfig`() {
        val config = json.decodeFromString(LightConfigResult.serializer(), """{"id":0,"name":"Plafond","initial_state":"off"}""")
        assertEquals("Plafond", config.name)
    }
}
