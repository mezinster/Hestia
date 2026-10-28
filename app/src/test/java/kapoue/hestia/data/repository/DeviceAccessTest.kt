package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.AccessIssue
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccessTest {

    private fun device(ip: String, ip2: String? = null, pin: String? = null, ch: Int = 0) =
        Device(name = "d", ipAddress = ip, switchId = ch, type = DeviceType.PLUG, ip2Address = ip2, certPin = pin)

    @Test
    fun `pins cover both addresses`() {
        val map = pinsByAddress(listOf(device("a", "b", "P")))
        assertEquals(mapOf("a" to "P", "b" to "P"), map)
        assertEquals("P", map["a"])
        assertEquals("P", map["b"])
    }

    @Test
    fun `unpinned devices and blank second address are skipped`() {
        assertEquals(emptyMap<String, String>(), pinsByAddress(listOf(device("a", "b", null))))
        assertEquals(mapOf("a" to "P"), pinsByAddress(listOf(device("a", "", "P"))))
    }

    @Test
    fun `every channel of a strip maps to the same pin`() {
        val strip = (0..2).map { device("a", pin = "P", ch = it) }
        assertEquals(mapOf("a" to "P"), pinsByAddress(strip))
    }

    @Test
    fun `issue of the second address is reported`() {
        assertEquals(
            AccessIssue.HTTPS_REQUIRED,
            accessIssueFor(device("a", "b"), mapOf("b" to AccessIssue.HTTPS_REQUIRED)),
        )
    }

    @Test
    fun `certificate change wins over https required`() {
        val issues = mapOf("a" to AccessIssue.HTTPS_REQUIRED, "b" to AccessIssue.CERT_CHANGED)
        assertEquals(AccessIssue.CERT_CHANGED, accessIssueFor(device("a", "b"), issues))
        assertNull(accessIssueFor(device("a", "b"), emptyMap()))
    }

    @Test
    fun `certificate change on ip2 wins over a wrong password on ip1`() {
        val issues = mapOf("a" to AccessIssue.PASSWORD_WRONG, "b" to AccessIssue.CERT_CHANGED)
        assertEquals(AccessIssue.CERT_CHANGED, accessIssueFor(device("a", "b"), issues))
    }

    @Test
    fun `wrong password wins over unverified`() {
        val issues = mapOf("a" to AccessIssue.PASSWORD_UNVERIFIED, "b" to AccessIssue.PASSWORD_WRONG)
        assertEquals(AccessIssue.PASSWORD_WRONG, accessIssueFor(device("a", "b"), issues))
    }

    @Test
    fun `a password is suspended only when a known pin changes or is dropped`() {
        // Certificat changé puis accepté (D1).
        assertTrue(passwordMustBeSuspended(savedPin = "A", newPin = "B", hasPassword = true))
        // Certificat oublié (finding 1).
        assertTrue(passwordMustBeSuspended(savedPin = "A", newPin = null, hasPassword = true))
        // Même certificat reconfirmé, premier épinglage, ou aucun mot de passe : rien à retirer.
        assertFalse(passwordMustBeSuspended(savedPin = "A", newPin = "A", hasPassword = true))
        assertFalse(passwordMustBeSuspended(savedPin = null, newPin = "B", hasPassword = true))
        assertFalse(passwordMustBeSuspended(savedPin = "A", newPin = "B", hasPassword = false))
        assertFalse(passwordMustBeSuspended(savedPin = "A", newPin = null, hasPassword = false))
    }
}
