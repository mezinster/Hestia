package kapoue.hestia.data.repository

import kapoue.hestia.data.local.dao.updateFreshRows
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Revue finale C1, F2 : une écriture de groupe partie d'un instantané périmé (écran Modifier ouvert
 * avant « Faire confiance » / « Oublier ») ne doit jamais réécrire l'épingle HTTPS.
 */
class DeviceGroupWritesTest {
    private val ip = "192.168.1.96"

    /** Base factice, par identifiant. */
    private val db = mutableMapOf<Long, Device>()
    private var writes = 0

    private suspend fun apply(ids: List<Long>, transform: (Device) -> Device) =
        updateFreshRows(ids, read = { db[it] }, write = { writes++; db[it.id] = it }, transform = transform)

    private fun channel(id: Long, ch: Int, pin: String?, cloudId: String? = null) = Device(
        id = id, name = "c$ch", deviceName = "Prise", ipAddress = ip, switchId = ch,
        type = DeviceType.PLUG, certPin = pin, cloudId = cloudId,
    )

    @Test fun `group save from a stale snapshot keeps a pin trusted since`() = runTest {
        val snapshot = listOf(channel(1, 0, pin = null), channel(2, 1, pin = null))
        // « Faire confiance » écrit l'épingle en base après l'ouverture de l'écran.
        snapshot.forEach { db[it.id] = it.copy(certPin = "P") }
        apply(snapshot.map { it.id }) {
            it.withSharedFields("Salon", ip, null, null, null, DeviceType.LAMP)
        }
        assertEquals(listOf("P", "P"), db.values.map { it.certPin })
        assertEquals(listOf("Salon", "Salon"), db.values.map { it.deviceName })
        assertEquals(listOf(DeviceType.LAMP, DeviceType.LAMP), db.values.map { it.type })
        // Le nom de canal individuel n'est jamais touché.
        assertEquals(listOf("c0", "c1"), db.values.map { it.name })
    }

    @Test fun `group save from a stale snapshot does not bring back a forgotten pin`() = runTest {
        val snapshot = listOf(channel(1, 0, pin = "P"), channel(2, 1, pin = "P"))
        snapshot.forEach { db[it.id] = it.copy(certPin = null) } // « Oublier »
        apply(snapshot.map { it.id }) { it.withSharedFields("Salon", ip, null, null, null, DeviceType.PLUG) }
        db.values.forEach { assertNull(it.certPin) }
    }

    @Test fun `cloud id cache from a stale snapshot keeps the pin and skips unchanged rows`() = runTest {
        val snapshot = listOf(channel(1, 0, pin = null), channel(2, 1, pin = null, cloudId = "MAC"))
        snapshot.forEach { db[it.id] = it.copy(certPin = "P") }
        apply(snapshot.map { it.id }) { it.withCloudId("MAC") }
        assertEquals(listOf("P", "P"), db.values.map { it.certPin })
        assertEquals(listOf("MAC", "MAC"), db.values.map { it.cloudId })
        assertEquals(1, writes)
    }

    @Test fun `a row deleted since the snapshot is skipped`() = runTest {
        db[1] = channel(1, 0, pin = "P")
        apply(listOf(1, 2)) { it.withCloudId("MAC") }
        assertEquals(setOf(1L), db.keys)
        assertEquals(1, writes)
    }
}
