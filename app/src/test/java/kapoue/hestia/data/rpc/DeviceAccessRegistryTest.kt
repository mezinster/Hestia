package kapoue.hestia.data.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccessRegistryTest {
    private val ip = "192.168.1.96"

    /** Base factice : les tests y « écrivent » puis relisent via [DeviceAccessRegistry.reload]. */
    private var db: Map<String, String> = emptyMap()
    private fun registry() = DeviceAccessRegistry(SavedPinSource { db })

    @Test fun `provisional pin wins over saved pin`() = runTest {
        val r = registry()
        db = mapOf(ip to "A")
        r.reload()
        r.trustProvisionally(ip, "B")
        assertEquals("B", r.pinFor(ip))
    }

    @Test fun `forgetting a provisional pin falls back to the saved one, or to none`() = runTest {
        val r = registry()
        db = mapOf(ip to "A")
        r.reload()
        r.trustProvisionally(ip, "B")
        r.forgetProvisional(ip)
        assertEquals("A", r.pinFor(ip))
        db = emptyMap()
        r.reload()
        r.trustProvisionally(ip, "B")
        r.forgetProvisional(ip)
        assertNull(r.pinFor(ip))
    }

    @Test fun `reload replaces saved pins entirely`() = runTest {
        val r = registry()
        val a = "10.0.0.1"
        val b = "10.0.0.2"
        db = mapOf(a to "A", b to "B")
        r.reload()
        db = mapOf(a to "A2")
        r.reload()
        assertEquals("A2", r.pinFor(a))
        assertNull(r.pinFor(b))
    }

    @Test fun `report sets and clears an issue`() {
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() })
        r.report(ip, AccessIssue.CERT_CHANGED)
        assertEquals(mapOf(ip to AccessIssue.CERT_CHANGED), r.issues.value)
        r.report(ip, null)
        assertTrue(r.issues.value.isEmpty())
    }

    @Test fun `saved pins are loaded on first ensureLoaded`() = runTest {
        val r = DeviceAccessRegistry(SavedPinSource { mapOf(ip to "P") })
        assertNull(r.pinFor(ip))
        assertTrue(r.ensureLoaded())
        assertEquals("P", r.pinFor(ip))
    }

    @Test fun `concurrent ensureLoaded callers share one load`() = runTest {
        var loads = 0
        val gate = CompletableDeferred<Map<String, String>>()
        val r = DeviceAccessRegistry(SavedPinSource { loads++; gate.await() })
        val first = async { r.ensureLoaded() }
        val second = async { r.ensureLoaded() }
        advanceUntilIdle()
        // Les deux appelants attendent la même lecture, encore en cours.
        assertEquals(1, loads)
        assertFalse(first.isCompleted || second.isCompleted)
        gate.complete(mapOf(ip to "P"))
        assertEquals(listOf(true, true), listOf(first, second).awaitAll())
        assertTrue(r.ensureLoaded())
        assertEquals(1, loads)
        assertEquals("P", r.pinFor(ip))
    }

    @Test fun `a reload started after a write sees the write`() = runTest {
        var db = mapOf(ip to "OLD")
        val r = DeviceAccessRegistry(SavedPinSource { db })
        assertTrue(r.ensureLoaded())
        assertEquals("OLD", r.pinFor(ip))
        db = mapOf(ip to "NEW") // écriture validée en base
        assertTrue(r.reload())
        assertEquals("NEW", r.pinFor(ip))
    }

    @Test fun `an older in-flight read cannot overwrite a newer reload`() = runTest {
        // Lecture n°1 (ancienne, lente) puis n°2 (récente, déjà prête) : si elles se chevauchaient,
        // la n°2 finirait d'abord et la n°1 écraserait ensuite l'épingle récente par l'ancienne.
        val gates = listOf(
            CompletableDeferred<Map<String, String>>(),
            CompletableDeferred(mapOf(ip to "NEW")),
        )
        var reads = 0
        val r = DeviceAccessRegistry(SavedPinSource { gates[reads++].await() })
        val older = async { r.reload() }
        advanceUntilIdle()
        val newer = async { r.reload() }
        advanceUntilIdle()
        // La lecture récente attend que l'ancienne soit terminée.
        assertEquals(1, reads)
        gates[0].complete(mapOf(ip to "OLD"))
        assertEquals(listOf(true, true), listOf(older, newer).awaitAll())
        assertEquals(2, reads)
        assertEquals("NEW", r.pinFor(ip))
    }

    @Test fun `a failed reload keeps the previous pins`() = runTest {
        var fail = false
        val r = DeviceAccessRegistry(SavedPinSource { if (fail) throw IllegalStateException("base illisible") else mapOf(ip to "P") })
        assertTrue(r.reload())
        fail = true
        assertFalse(r.reload())
        assertEquals("P", r.pinFor(ip))
        assertTrue(r.ensureLoaded())
    }

    @Test fun `a failed first reload leaves the registry unloaded`() = runTest {
        var fail = true
        val r = DeviceAccessRegistry(SavedPinSource { if (fail) throw IllegalStateException("base illisible") else mapOf(ip to "P") })
        assertFalse(r.reload())
        fail = false
        assertTrue(r.ensureLoaded())
        assertEquals("P", r.pinFor(ip))
    }

    @Test fun `failed load is retried and reported`() = runTest {
        var loads = 0
        val r = DeviceAccessRegistry(
            SavedPinSource {
                loads++
                if (loads == 1) throw IllegalStateException("base illisible")
                mapOf(ip to "P")
            },
        )
        assertFalse(r.ensureLoaded())
        assertNull(r.pinFor(ip))
        assertTrue(r.ensureLoaded())
        assertEquals("P", r.pinFor(ip))
    }

    @Test fun `reload counts as loaded`() = runTest {
        var loads = 0
        val r = DeviceAccessRegistry(SavedPinSource { loads++; mapOf(ip to "P") })
        assertTrue(r.reload())
        assertTrue(r.ensureLoaded())
        assertEquals(1, loads)
        assertEquals("P", r.pinFor(ip))
    }
}
