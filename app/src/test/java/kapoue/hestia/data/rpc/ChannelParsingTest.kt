package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.ComponentEntry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixtures d'après les exemples `Shelly.GetComponents` de la doc officielle Gen2+. */
class ChannelParsingTest {

    private fun entry(key: String, name: String? = null) = ComponentEntry(
        key = key,
        config = name?.let { buildJsonObject { put("name", JsonPrimitive(it)) } },
    )

    @Test
    fun `un variateur expose un canal light`() {
        val components = listOf(entry("sys"), entry("input:0"), entry("light:0", "Salon"))
        assertEquals(listOf(0), parseChannels(components, "light"))
        assertEquals(emptyList<Int>(), parseChannels(components, "switch"))
        assertEquals(mapOf(0 to "Salon"), parseChannelNames(components, "light"))
    }

    @Test
    fun `un RGBW en mode light expose quatre canaux tries`() {
        val components = listOf(entry("light:3"), entry("light:0"), entry("light:2"), entry("light:1"))
        assertEquals(listOf(0, 1, 2, 3), parseChannels(components, "light"))
    }

    @Test
    fun `switch et light sont extraits separement`() {
        val components = listOf(entry("switch:0"), entry("light:1"))
        assertEquals(listOf(0), parseChannels(components, "switch"))
        assertEquals(listOf(1), parseChannels(components, "light"))
    }

    @Test
    fun `un nom vide est ignore`() {
        assertEquals(emptyMap<Int, String>(), parseChannelNames(listOf(entry("light:0", " ")), "light"))
    }

    @Test
    fun `lightswitch ou rgbw ne sont pas confondus avec light`() {
        assertEquals(emptyList<Int>(), parseChannels(listOf(entry("rgbw:0"), entry("lights:0")), "light"))
    }

    @Test
    fun `un Pro Dual Cover expose deux canaux volet`() {
        val components = listOf(entry("cover:1", "Chambre"), entry("cover:0"), entry("input:0"))
        assertEquals(listOf(0, 1), parseChannels(components, "cover"))
        assertEquals(mapOf(1 to "Chambre"), parseChannelNames(components, "cover"))
    }
}
