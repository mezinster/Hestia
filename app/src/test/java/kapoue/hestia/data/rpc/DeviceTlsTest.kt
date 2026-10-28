package kapoue.hestia.data.rpc

import okhttp3.CertificatePinner
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException

class DeviceTlsTest {
    private val held = HeldCertificate.Builder().commonName("shellyplugmg3-test").build()
    private val other = HeldCertificate.Builder().commonName("intruder").build()

    @Test fun `pin is sha256 of the public key in OkHttp format`() {
        assertEquals(CertificatePinner.pin(held.certificate), CertPins.pinOf(held.certificate))
    }

    @Test fun `fingerprint is 32 colon-separated upper-case hex bytes`() {
        assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(CertPins.fingerprintOf(held.certificate)))
    }

    @Test fun `pinned trust manager accepts the pinned key`() {
        PinnedTrustManager(CertPins.pinOf(held.certificate)).checkServerTrusted(arrayOf(held.certificate), "RSA")
    }

    @Test(expected = PinMismatchException::class) fun `pinned trust manager rejects another key`() {
        PinnedTrustManager(CertPins.pinOf(held.certificate)).checkServerTrusted(arrayOf(other.certificate), "RSA")
    }

    @Test(expected = CertificateException::class) fun `pinned trust manager rejects an empty chain`() {
        PinnedTrustManager("sha256/x").checkServerTrusted(emptyArray(), "RSA")
    }

    @Test fun `address without port uses the default`() {
        assertEquals("192.168.1.96", hostOf("192.168.1.96"))
        assertEquals(443, portOf("192.168.1.96", 443))
        assertEquals("127.0.0.1", hostOf("127.0.0.1:8443"))
        assertEquals(8443, portOf("127.0.0.1:8443", 443))
    }
}
