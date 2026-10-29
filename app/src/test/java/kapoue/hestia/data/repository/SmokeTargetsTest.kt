package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.presence.SmokeRelayScriptGenerator
import kapoue.hestia.data.rpc.AccessIssue
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTargetsTest {
    private val ip = "192.168.1.50"

    private fun device(ip: String = this.ip, ip2: String? = null, pin: String? = null) =
        Device(name = "d", ipAddress = ip, switchId = 0, type = DeviceType.PLUG, ip2Address = ip2, certPin = pin)

    private fun gate(
        devices: List<Device> = listOf(device()),
        issues: Map<String, AccessIssue> = emptyMap(),
        pw: Set<String> = emptySet(),
    ) = SmokeTargetGate(devices, issues, pw)

    @Test fun `open device is allowed`() = assertNull(gate().blockOf(ip))

    @Test fun `saved password blocks as AUTH`() =
        assertEquals(SmokeTargetBlock.AUTH, gate(pw = setOf(ip)).blockOf(ip))

    @Test fun `password issues block as AUTH`() {
        for (issue in listOf(
            AccessIssue.PASSWORD_REQUIRED, AccessIssue.PASSWORD_WRONG,
            AccessIssue.PASSWORD_UNVERIFIED, AccessIssue.THROTTLED,
        )) {
            assertEquals("$issue", SmokeTargetBlock.AUTH, gate(issues = mapOf(ip to issue)).blockOf(ip))
        }
    }

    @Test fun `pinned device blocks as HTTPS`() =
        assertEquals(SmokeTargetBlock.HTTPS, gate(listOf(device(pin = "sha256/x"))).blockOf(ip))

    @Test fun `https issues block as HTTPS`() {
        for (issue in listOf(AccessIssue.HTTPS_REQUIRED, AccessIssue.CERT_CHANGED)) {
            assertEquals("$issue", SmokeTargetBlock.HTTPS, gate(issues = mapOf(ip to issue)).blockOf(ip))
        }
    }

    @Test fun `AUTH wins over HTTPS`() =
        assertEquals(SmokeTargetBlock.AUTH, gate(listOf(device(pin = "sha256/x")), pw = setOf(ip)).blockOf(ip))

    @Test fun `blocks an address when the other address of the same device is protected`() {
        val g = gate(listOf(device(ip2 = "10.0.0.50")), issues = mapOf("10.0.0.50" to AccessIssue.PASSWORD_REQUIRED))
        assertEquals(SmokeTargetBlock.AUTH, g.blockOf(ip))
        assertEquals(SmokeTargetBlock.AUTH, g.blockOf(g.devices.first()))
    }

    @Test fun `unknown address only uses its own entries`() {
        assertEquals(SmokeTargetBlock.AUTH, gate(pw = setOf("192.168.1.99")).blockOf("192.168.1.99"))
        assertNull(gate().blockOf("192.168.1.99"))
    }

    @Test fun `cutoff url round-trips`() {
        val t = DeviceRepository.SmokeCutoffTarget(ip, 2)
        assertEquals("http://192.168.1.50/rpc/Switch.Set?id=2&on=false", cutoffTargetUrl(t))
        assertEquals(t, parseCutoffTargetUrl(cutoffTargetUrl(t)))
    }

    @Test fun `no credentials in any smoke webhook url`() {
        val urls = listOf(
            cutoffTargetUrl(DeviceRepository.SmokeCutoffTarget(ip, 0)),
            relayHookUrl(ip, 3, "smoke.alarm", "AABBCCDDEEFF"),
        )
        for (url in urls) {
            assertTrue(url, url.startsWith("http://192.168.1.50/"))
            assertFalse(url, url.contains('@'))
            assertFalse(url, url.contains("admin", ignoreCase = true))
            assertNull(url, java.net.URI(url).userInfo)
        }
    }

    @Test fun `relay script carries no credentials`() {
        val script = SmokeRelayScriptGenerator.generate(
            "topic", listOf(SmokeRelayScriptGenerator.DeviceName("AABBCCDDEEFF", "Hall")),
            SmokeRelayScriptGenerator.Bodies("a", "b", "c"),
        )
        assertFalse(script.contains("Authorization"))
        assertFalse(script.contains("admin:"))
    }
}
