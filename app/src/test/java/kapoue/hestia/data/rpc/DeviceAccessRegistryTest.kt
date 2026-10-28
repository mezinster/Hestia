package kapoue.hestia.data.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccessRegistryTest {
    private val ip = "192.168.1.96"

    @Test fun `provisional pin wins over saved pin`() {
        val r = DeviceAccessRegistry()
        r.syncSavedPins(mapOf(ip to "A"))
        r.trustProvisionally(ip, "B")
        assertEquals("B", r.pinFor(ip))
    }

    @Test fun `forgetting a provisional pin falls back to the saved one, or to none`() {
        val r = DeviceAccessRegistry()
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
        val r = DeviceAccessRegistry()
        val a = "10.0.0.1"
        val b = "10.0.0.2"
        r.syncSavedPins(mapOf(a to "A", b to "B"))
        r.syncSavedPins(mapOf(a to "A2"))
        assertEquals("A2", r.pinFor(a))
        assertNull(r.pinFor(b))
    }

    @Test fun `report sets and clears an issue`() {
        val r = DeviceAccessRegistry()
        r.report(ip, AccessIssue.CERT_CHANGED)
        assertEquals(mapOf(ip to AccessIssue.CERT_CHANGED), r.issues.value)
        r.report(ip, null)
        assertTrue(r.issues.value.isEmpty())
    }
}
