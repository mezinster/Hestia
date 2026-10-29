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

    // --- Filtrage des cibles, candidats relais et marques « à revoir » (Tâche 2) ---

    private fun tgt(last: Int, id: Int) = DeviceRepository.SmokeCutoffTarget("192.168.1.$last", id)

    @Test fun `allowed targets keep open and drop protected`() {
        val g = gate(pw = setOf("192.168.1.51"))
        val (kept, dropped) = allowedCutoffTargets(listOf(tgt(50, 0), tgt(51, 0), tgt(52, 1)), g)
        assertEquals(listOf(tgt(50, 0), tgt(52, 1)), kept)
        assertEquals(listOf(tgt(51, 0)), dropped)
    }

    @Test fun `relay candidates skip protected and keep one per address`() {
        fun scriptable(ip: String, ch: Int, pin: String? = null, scripting: Boolean = true) =
            Device(name = "d$ch", ipAddress = ip, switchId = ch, type = DeviceType.PLUG, hasScripting = scripting, certPin = pin)
        val a0 = scriptable("192.168.1.50", 0)
        val all = listOf(
            a0, scriptable("192.168.1.50", 1),
            scriptable("192.168.1.51", 0, pin = "sha256/x"),
            scriptable("192.168.1.52", 0, scripting = false),
        )
        val g = SmokeTargetGate(all, emptyMap(), emptySet())
        assertEquals(listOf(a0), relayCandidates(all, g))
    }

    private val blockedGate get() = gate(pw = setOf("192.168.1.51"))
    private val blockedState = DeviceRepository.SmokeCutoffState.Configured(listOf(tgt(51, 0)))
    private val cleanState = DeviceRepository.SmokeCutoffState.Configured(listOf(tgt(50, 0)))

    @Test fun `configured read with a blocked target sets the mark`() =
        assertEquals(setOf(7L), reviewMarksAfterRead(emptySet(), 7L, blockedState, blockedGate))

    @Test fun `configured read without blocked target clears the mark`() =
        assertEquals(emptySet<Long>(), reviewMarksAfterRead(setOf(7L), 7L, cleanState, blockedGate))

    @Test fun `unknown read keeps the mark`() {
        val unknown = DeviceRepository.SmokeCutoffState.Unknown
        assertEquals(setOf(7L), reviewMarksAfterRead(setOf(7L), 7L, unknown, blockedGate))
        assertEquals(emptySet<Long>(), reviewMarksAfterRead(emptySet(), 7L, unknown, blockedGate))
    }
}
