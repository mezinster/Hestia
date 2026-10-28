package kapoue.hestia.data.rpc

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccessRegistryTest {
    private val ip = "192.168.1.96"

    @Test fun `provisional pin wins over saved pin`() {
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() })
        r.syncSavedPins(mapOf(ip to "A"))
        r.trustProvisionally(ip, "B")
        assertEquals("B", r.pinFor(ip))
    }

    @Test fun `forgetting a provisional pin falls back to the saved one, or to none`() {
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() })
        r.syncSavedPins(mapOf(ip to "A"))
        r.trustProvisionally(ip, "B")
        r.forgetProvisional(ip)
        assertEquals("A", r.pinFor(ip))
        r.syncSavedPins(emptyMap())
        r.trustProvisionally(ip, "B")
        r.forgetProvisional(ip)
        assertNull(r.pinFor(ip))
    }

    @Test fun `sync replaces saved pins entirely`() {
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() })
        val a = "10.0.0.1"
        val b = "10.0.0.2"
        r.syncSavedPins(mapOf(a to "A", b to "B"))
        r.syncSavedPins(mapOf(a to "A2"))
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

    @Test fun `load happens once`() = runTest {
        var loads = 0
        val r = DeviceAccessRegistry(SavedPinSource { loads++; mapOf(ip to "P") })
        val results = listOf(async { r.ensureLoaded() }, async { r.ensureLoaded() }).awaitAll()
        assertTrue(r.ensureLoaded())
        assertEquals(listOf(true, true), results)
        assertEquals(1, loads)
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

    @Test fun `sync counts as loaded`() = runTest {
        var loads = 0
        val r = DeviceAccessRegistry(SavedPinSource { loads++; emptyMap() })
        r.syncSavedPins(mapOf(ip to "P"))
        assertTrue(r.ensureLoaded())
        assertEquals(0, loads)
        assertEquals("P", r.pinFor(ip))
    }
}
