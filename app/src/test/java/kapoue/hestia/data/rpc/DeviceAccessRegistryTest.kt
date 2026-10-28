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

    // --- Mots de passe (C2) ---

    private class FakePasswords(var data: Map<String, StoredPassword> = emptyMap()) : SavedPasswordSource {
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var fail = false
        var failMark = false
        /** Alias : adresse -> adresse principale, pour simuler ip2 d'un même canal. */
        var aliases: Map<String, String> = emptyMap()
        override suspend fun load(): Map<String, StoredPassword> {
            if (fail) error("boom")
            return data
        }
        override suspend fun markAccepted(address: String, expectedPassword: String) {
            if (failMark) error("boom")
            accepted += address
            setState(address, expectedPassword, PasswordState.ACCEPTED)
        }
        override suspend fun markRejected(address: String, expectedPassword: String) {
            if (failMark) error("boom")
            rejected += address
            setState(address, expectedPassword, PasswordState.REJECTED)
        }
        private fun setState(address: String, expected: String, state: PasswordState) {
            val main = aliases[address] ?: address
            val cur = data[main] ?: return
            if (cur.password != expected) return
            data = data.mapValues { (k, v) -> if (k == main || aliases[k] == main) v.copy(state = state) else v }
        }
    }

    private fun pw(state: PasswordState) = StoredPassword("secret", state)

    @Test fun `a rejected saved password is never handed out`() = runTest {
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, FakePasswords(mapOf(ip to pw(PasswordState.REJECTED))))
        r.ensureLoaded()
        assertNull(r.passwordFor(ip))
        assertEquals(RpcFailure.PASSWORD_WRONG, r.blockedFor(ip))
        assertEquals(AccessIssue.PASSWORD_WRONG, r.issues.value[ip])
    }

    @Test fun `an unverified password is flagged until accepted`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.ensureLoaded()
        assertEquals("secret", r.passwordFor(ip))
        assertEquals(AccessIssue.PASSWORD_UNVERIFIED, r.issues.value[ip])
        r.onAuthAccepted(ip, "secret")
        assertEquals(listOf(ip), src.accepted)
        assertNull(r.issues.value[ip])
    }

    @Test fun `a provisional password wins and a new entry clears its rejection`() = runTest {
        val r = registry()
        r.trustPasswordProvisionally("x", "a")
        assertEquals("a", r.passwordFor("x"))
        r.onAuthRejected("x", "a")
        assertEquals(RpcFailure.PASSWORD_WRONG, r.blockedFor("x"))
        assertNull(r.passwordFor("x"))
        r.trustPasswordProvisionally("x", "b")
        assertNull(r.blockedFor("x"))
        assertEquals("b", r.passwordFor("x"))
    }

    @Test fun `provisionalPasswordFor ignores saved passwords`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.ACCEPTED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        assertNull(r.provisionalPasswordFor(ip))
        r.trustPasswordProvisionally(ip, "new")
        assertEquals("new", r.provisionalPasswordFor(ip))
    }

    @Test fun `throttle lasts sixty seconds`() {
        val r = registry()
        r.now = { 0 }
        r.onThrottled("x")
        assertEquals(RpcFailure.THROTTLED, r.blockedFor("x"))
        r.now = { 60_000 }
        assertNull(r.blockedFor("x"))
    }

    @Test fun `saving a new password clears a saved rejection on reload`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.REJECTED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        src.data = mapOf(ip to pw(PasswordState.UNVERIFIED))
        r.reload()
        assertNull(r.blockedFor(ip))
        assertEquals(AccessIssue.PASSWORD_UNVERIFIED, r.issues.value[ip])
    }

    @Test fun `a removed password clears its issues`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        src.data = emptyMap()
        r.reload()
        assertNull(r.issues.value[ip])
    }

    @Test fun `password load failure fails closed`() = runTest {
        val src = FakePasswords().apply { fail = true }
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        assertFalse(r.ensureLoaded())
    }

    @Test fun `a saved rejection is persisted and applied in memory`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.ensureLoaded()
        r.onAuthRejected(ip, "secret")
        assertEquals(listOf(ip), src.rejected)
        assertEquals(RpcFailure.PASSWORD_WRONG, r.blockedFor(ip))
    }

    @Test fun `sessions are kept per target and removable`() {
        val r = registry()
        val s = DigestSession("realm", "nonce")
        r.setSession("https://1.2.3.4", s)
        assertEquals(s, r.sessionFor("https://1.2.3.4"))
        assertNull(r.sessionFor("http://1.2.3.4"))
        r.setSession("https://1.2.3.4", null)
        assertNull(r.sessionFor("https://1.2.3.4"))
        assertTrue(r.lockFor("a") === r.lockFor("a"))
    }

    @Test fun `a stale rejection does not touch a newly saved password`() = runTest {
        val src = FakePasswords(mapOf(ip to StoredPassword("P1", PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        src.data = mapOf(ip to StoredPassword("P2", PasswordState.UNVERIFIED))
        r.reload()
        r.onAuthRejected(ip, "P1")
        assertTrue(src.rejected.isEmpty())
        assertEquals("P2", r.passwordFor(ip))
        assertNull(r.blockedFor(ip))
        assertEquals(PasswordState.UNVERIFIED, src.data[ip]?.state)
    }

    @Test fun `a stale acceptance does not touch a newly saved password`() = runTest {
        val src = FakePasswords(mapOf(ip to StoredPassword("P1", PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        src.data = mapOf(ip to StoredPassword("P2", PasswordState.UNVERIFIED))
        r.reload()
        r.onAuthAccepted(ip, "P1")
        assertTrue(src.accepted.isEmpty())
        assertEquals(AccessIssue.PASSWORD_UNVERIFIED, r.issues.value[ip])
    }

    @Test fun `a rejection via one address blocks its alias`() = runTest {
        val ip2 = "10.0.0.5"
        val entry = StoredPassword("secret", PasswordState.UNVERIFIED)
        val src = FakePasswords(mapOf(ip to entry, ip2 to entry)).apply { aliases = mapOf(ip2 to ip) }
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        r.onAuthRejected(ip, "secret")
        assertEquals(RpcFailure.PASSWORD_WRONG, r.blockedFor(ip2))
        assertNull(r.passwordFor(ip2))
    }

    @Test fun `load-time sync never overwrites CERT_CHANGED or HTTPS_REQUIRED`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.REJECTED), "b" to pw(PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.report(ip, AccessIssue.CERT_CHANGED)
        r.report("b", AccessIssue.HTTPS_REQUIRED)
        r.reload()
        assertEquals(AccessIssue.CERT_CHANGED, r.issues.value[ip])
        assertEquals(AccessIssue.HTTPS_REQUIRED, r.issues.value["b"])
    }

    @Test fun `rejection replaces required, throttled and unverified but not a certificate issue`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.UNVERIFIED), "b" to pw(PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        r.report(ip, AccessIssue.THROTTLED)
        r.report("b", AccessIssue.CERT_CHANGED)
        r.onAuthRejected(ip, "secret")
        r.onAuthRejected("b", "secret")
        assertEquals(AccessIssue.PASSWORD_WRONG, r.issues.value[ip])
        assertEquals(AccessIssue.CERT_CHANGED, r.issues.value["b"])
    }

    @Test fun `a provisional acceptance is not persisted`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        r.trustPasswordProvisionally(ip, "secret")
        r.onAuthAccepted(ip, "secret")
        assertTrue(src.accepted.isEmpty())
    }

    @Test fun `a failing source mark does not throw and still blocks`() = runTest {
        val src = FakePasswords(mapOf(ip to pw(PasswordState.UNVERIFIED))).apply { failMark = true }
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, src)
        r.reload()
        r.onAuthRejected(ip, "secret")
        assertEquals(RpcFailure.PASSWORD_WRONG, r.blockedFor(ip))
    }

    @Test fun `a reload where pins succeed but passwords fail keeps the previous state`() = runTest {
        db = mapOf(ip to "A")
        val src = FakePasswords(mapOf(ip to pw(PasswordState.UNVERIFIED)))
        val r = DeviceAccessRegistry(SavedPinSource { db }, src)
        assertTrue(r.reload())
        db = mapOf(ip to "B")
        src.fail = true
        assertFalse(r.reload())
        assertEquals("A", r.pinFor(ip))
        assertEquals("secret", r.passwordFor(ip))
    }

    @Test fun `a new provisional password drops the existing sessions`() = runTest {
        val r = registry()
        r.setSession("http://$ip", DigestSession("r", "n1"))
        r.setSession("https://$ip", DigestSession("r", "n2"))
        val kept = DigestSession("r", "n3")
        r.setSession("http://other", kept)
        r.trustPasswordProvisionally(ip, "new")
        assertNull(r.sessionFor("http://$ip"))
        assertNull(r.sessionFor("https://$ip"))
        assertTrue(r.sessionFor("http://other") === kept)
        r.setSession("https://$ip", DigestSession("r", "n4"))
        r.forgetProvisionalPassword(ip)
        assertNull(r.sessionFor("https://$ip"))
        r.setSession("http://$ip", DigestSession("r", "n5"))
        r.clearSessions(ip)
        assertNull(r.sessionFor("http://$ip"))
    }

    @Test fun `an accepted provisional password is remembered as accepted`() = runTest {
        val r = DeviceAccessRegistry(SavedPinSource { emptyMap() }, FakePasswords())
        r.reload()
        r.trustPasswordProvisionally(ip, "secret")
        assertFalse(r.provisionalPasswordAccepted(ip))
        r.onAuthAccepted(ip, "other")
        assertFalse(r.provisionalPasswordAccepted(ip))
        r.onAuthAccepted(ip, "secret")
        assertTrue(r.provisionalPasswordAccepted(ip))
        // Nouvelle saisie : l'acceptation précédente ne vaut plus.
        r.trustPasswordProvisionally(ip, "secret2")
        assertFalse(r.provisionalPasswordAccepted(ip))
        r.onAuthAccepted(ip, "secret2")
        r.forgetProvisionalPassword(ip)
        assertFalse(r.provisionalPasswordAccepted(ip))
    }

    @Test fun `https seen can be cleared per address`() {
        val r = registry()
        r.markHttpsSeen(ip)
        r.markHttpsSeen("b")
        r.clearHttpsSeen(ip)
        assertFalse(r.httpsSeen(ip))
        assertTrue(r.httpsSeen("b"))
    }
}
