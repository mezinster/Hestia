package kapoue.hestia.ui.screens.device

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.AccessIssue
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TlsStateTest {

    private fun device(pin: String?) =
        Device(name = "Prise", ipAddress = "192.168.1.50", type = DeviceType.PLUG, certPin = pin)

    @Test
    fun `plain http device has no section`() {
        assertNull(tlsStateOf(device(null), emptyMap()))
    }

    @Test
    fun `pinned device is trusted`() {
        assertEquals(TlsState.TRUSTED, tlsStateOf(device("P"), emptyMap()))
    }

    @Test
    fun `redirect makes it required`() {
        val issues = mapOf("192.168.1.50" to AccessIssue.HTTPS_REQUIRED)
        assertEquals(TlsState.REQUIRED, tlsStateOf(device(null), issues))
    }

    @Test
    fun `pin mismatch shows changed even when pinned`() {
        val issues = mapOf("192.168.1.50" to AccessIssue.CERT_CHANGED)
        assertEquals(TlsState.CHANGED, tlsStateOf(device("P"), issues))
    }
}
