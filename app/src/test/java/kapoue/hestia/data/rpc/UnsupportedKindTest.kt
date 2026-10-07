package kapoue.hestia.data.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Listes de composants inspirées des exemples de `Shelly.GetComponents` de la documentation
 * officielle Gen2+ (shelly-api-docs.shelly.cloud/gen2) — aucun appareil physique nécessaire.
 */
class UnsupportedKindTest {

    private val common = listOf("ble", "cloud", "mqtt", "sys", "wifi", "ws")

    private fun caps(vararg keys: String) = DeviceCapabilities(
        generation = 2,
        model = null,
        reportedName = null,
        switchChannels = keys.mapNotNull { Regex("""switch:(\d+)""").matchEntire(it)?.groupValues?.get(1)?.toInt() },
        lightChannels = keys.mapNotNull { Regex("""light:(\d+)""").matchEntire(it)?.groupValues?.get(1)?.toInt() },
        coverChannels = keys.mapNotNull { Regex("""cover:(\d+)""").matchEntire(it)?.groupValues?.get(1)?.toInt() },
        componentKeys = common + keys,
        hasScripting = true,
        hasPowerMetering = false,
    )

    @Test
    fun `un relais avec un canal switch est pris en charge`() {
        assertNull(caps("input:0", "switch:0").unsupportedKind)
    }

    @Test
    fun `un 2PM en mode volet est pris en charge`() {
        assertNull(caps("input:0", "input:1", "cover:0").unsupportedKind)
    }

    @Test
    fun `un variateur est pris en charge`() {
        assertNull(caps("input:0", "light:0").unsupportedKind)
    }

    @Test
    fun `un contrôleur en mode couleur est refusé comme éclairage couleur`() {
        assertEquals(UnsupportedKind.COLOR_LIGHT, caps("input:0", "rgbw:0").unsupportedKind)
        assertEquals(UnsupportedKind.COLOR_LIGHT, caps("rgb:0").unsupportedKind)
        assertEquals(UnsupportedKind.COLOR_LIGHT, caps("cct:0").unsupportedKind)
    }

    @Test
    fun `un compteur d'énergie sans relais est reconnu comme compteur`() {
        assertEquals(UnsupportedKind.ENERGY_METER, caps("em:0", "emdata:0", "temperature:0").unsupportedKind)
        assertEquals(UnsupportedKind.ENERGY_METER, caps("em1:0", "em1:1").unsupportedKind)
        assertEquals(UnsupportedKind.ENERGY_METER, caps("pm1:0").unsupportedKind)
    }

    @Test
    fun `un détecteur de fumée ajouté comme prise est signalé comme tel`() {
        assertEquals(UnsupportedKind.SMOKE_DETECTOR, caps("smoke:0", "devicepower:0").unsupportedKind)
    }

    @Test
    fun `un capteur est reconnu comme capteur`() {
        assertEquals(UnsupportedKind.SENSOR, caps("temperature:0", "humidity:0", "devicepower:0").unsupportedKind)
        assertEquals(UnsupportedKind.SENSOR, caps("flood:0", "devicepower:0").unsupportedKind)
        assertEquals(UnsupportedKind.SENSOR, caps("illuminance:0").unsupportedKind)
    }

    @Test
    fun `un appareil à entrées seules est reconnu comme tel`() {
        assertEquals(UnsupportedKind.INPUT_ONLY, caps("input:0", "input:1", "input:2", "input:3").unsupportedKind)
    }

    @Test
    fun `un appareil sans rien de reconnaissable reste inconnu`() {
        assertEquals(UnsupportedKind.UNKNOWN, caps().unsupportedKind)
        assertEquals(UnsupportedKind.UNKNOWN, caps("bthome", "blugw").unsupportedKind)
    }
}
