package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.CoverStatusResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverRpcTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decode l'exemple Cover GetStatus de la doc`() {
        val s = json.decodeFromString(CoverStatusResult.serializer(), """{"id":0,"state":"open","apower":0,"current_pos":100,"pos_control":true,"last_direction":"open"}""")
        assertEquals("open", s.state); assertEquals(100, s.currentPos); assertTrue(s.posControl); assertTrue(s.errors.isEmpty())
    }

    @Test
    fun `decode un volet non calibre en mouvement avec erreur`() {
        val s = json.decodeFromString(CoverStatusResult.serializer(), """{"id":1,"state":"closing","current_pos":null,"pos_control":false,"errors":["obstruction"]}""")
        assertEquals("closing", s.state); assertNull(s.currentPos); assertFalse(s.posControl); assertEquals(listOf("obstruction"), s.errors)
    }

    @Test
    fun `parametres des appels`() {
        assertEquals("""{"id":1}""", coverIdParams(1).toString())
        assertEquals("""{"id":0,"pos":40}""", coverGoToParams(0, 40).toString())
        assertEquals("""{"id":0,"pos":100}""", coverGoToParams(0, 140).toString())
    }
}
