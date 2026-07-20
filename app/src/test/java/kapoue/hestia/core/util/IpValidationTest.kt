package kapoue.hestia.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpValidationTest {

    @Test
    fun `adresses valides`() {
        assertTrue(isValidIpv4("192.168.1.96"))
        assertTrue(isValidIpv4("10.0.0.1"))
        assertTrue(isValidIpv4("0.0.0.0"))
        assertTrue(isValidIpv4("255.255.255.255"))
    }

    @Test
    fun `adresses invalides`() {
        assertFalse(isValidIpv4(""))
        assertFalse(isValidIpv4("192.168.1"))
        assertFalse(isValidIpv4("192.168.1.256"))
        assertFalse(isValidIpv4("192.168.1.1.1"))
        assertFalse(isValidIpv4("a.b.c.d"))
        assertFalse(isValidIpv4("192.168.01.-1"))
        assertFalse(isValidIpv4("192.168. 1.1"))
    }
}
