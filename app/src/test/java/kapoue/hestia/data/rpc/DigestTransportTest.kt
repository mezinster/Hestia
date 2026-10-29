package kapoue.hestia.data.rpc

import kapoue.hestia.core.log.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Transport authentifié (digest SHA-256) : défi puis un seul renvoi, réutilisation de session,
 * refus définitif, 429, renouvellement de nonce, sérialisation, et jamais d'identifiants en clair
 * vers un appareil signalé HTTPS.
 */
class DigestTransportTest {

    private val logLines = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val logger = object : DiagnosticLogger {
        override fun info(tag: String, message: String) { logLines += message }
        override fun warn(tag: String, message: String) { logLines += message }
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** Fausse source de mots de passe : l'état accepté/refusé est bien reporté, comme la vraie. */
    private val passwords = ConcurrentHashMap<String, StoredPassword>()
    private val passwordSource = object : SavedPasswordSource {
        override suspend fun load(): Map<String, StoredPassword> = passwords.toMap()
        override suspend fun markAccepted(address: String, expectedPassword: String) {
            passwords.computeIfPresent(address) { _, s ->
                if (s.password == expectedPassword) s.copy(state = PasswordState.ACCEPTED) else s
            }
        }
        override suspend fun markRejected(address: String, expectedPassword: String) {
            passwords.computeIfPresent(address) { _, s ->
                if (s.password == expectedPassword) s.copy(state = PasswordState.REJECTED) else s
            }
        }
    }

    private val registry = DeviceAccessRegistry(SavedPinSource { emptyMap() }, passwordSource)
    private val client = ShellyRpcClient(
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        Json { ignoreUnknownKeys = true },
        logger,
        registry,
    )

    private val server = MockWebServer()
    private val held = HeldCertificate.Builder().commonName("shelly").build()
    private val address: String get() = "127.0.0.1:${server.port}"

    @After
    fun tearDown() {
        server.close()
    }

    private fun startHttp() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    private fun startHttps() {
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        server.useHttps(serverCerts.sslSocketFactory())
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun knownPassword(password: String = "secret") {
        passwords[address] = StoredPassword(password, PasswordState.UNVERIFIED)
    }

    private fun ok() = MockResponse.Builder().code(200).body("""{"id":1,"result":{}}""").build()

    private fun challenge(nonce: String = "n1") = MockResponse.Builder().code(401)
        .addHeader("WWW-Authenticate", """Digest qop="auth", realm="r", nonce="$nonce", algorithm=SHA-256""")
        .build()

    private fun take(): RecordedRequest = server.takeRequest(1, TimeUnit.SECONDS)!!

    private fun RecordedRequest.authorization(): String? = headers["Authorization"]

    private fun RecordedRequest.nc(): String? =
        authorization()?.let { Regex("""nc=([0-9a-f]{8})""").find(it)?.groupValues?.get(1) }

    private fun RecordedRequest.nonce(): String? =
        authorization()?.let { Regex("""nonce="([^"]*)"""").find(it)?.groupValues?.get(1) }

    @Test
    fun `challenge then one authenticated resend succeeds`() = runTest {
        startHttp()
        knownPassword()
        server.enqueue(challenge())
        server.enqueue(ok())
        val r = client.reboot(address)
        assertTrue("attendu Success, obtenu $r", r is RpcResult.Success)
        assertEquals(2, server.requestCount)
        assertNull(take().authorization())
        val second = take()
        assertEquals("00000001", second.nc())
        assertEquals("n1", second.nonce())
        assertTrue(second.authorization()!!.startsWith("Digest username=\"admin\""))
        // Mot de passe accepté : reporté à la source.
        assertEquals(PasswordState.ACCEPTED, passwords[address]!!.state)
        // Ni le mot de passe ni l'en-tête n'apparaissent au journal.
        assertTrue("journal : $logLines", logLines.none { it.contains("secret") || it.contains("Digest") })
    }

    @Test
    fun `next call reuses the session with the next nc`() = runTest {
        startHttp()
        knownPassword()
        server.enqueue(challenge())
        server.enqueue(ok())
        client.reboot(address)
        server.enqueue(ok())
        val r = client.reboot(address)
        assertTrue("attendu Success, obtenu $r", r is RpcResult.Success)
        assertEquals(3, server.requestCount)
        take(); take()
        assertEquals("00000002", take().nc())
    }

    @Test
    fun `wrong password stops after one resend and never retries`() = runTest {
        startHttp()
        knownPassword()
        server.enqueue(challenge())
        server.enqueue(challenge("n2"))
        assertEquals(RpcResult.Failure(RpcFailure.PASSWORD_WRONG), client.reboot(address))
        assertEquals(2, server.requestCount)
        assertEquals(AccessIssue.PASSWORD_WRONG, registry.issues.value[address])
        assertNull(registry.sessionFor("http://$address"))
        server.enqueue(ok())
        assertEquals(RpcResult.Failure(RpcFailure.PASSWORD_WRONG), client.reboot(address))
        assertEquals(2, server.requestCount)
        assertEquals(PasswordState.REJECTED, passwords[address]!!.state)
    }

    @Test
    fun `no password known reports PASSWORD_REQUIRED without any Authorization`() = runTest {
        startHttp()
        server.enqueue(challenge())
        assertEquals(RpcResult.Failure(RpcFailure.PASSWORD_REQUIRED), client.reboot(address))
        assertEquals(1, server.requestCount)
        assertNull(take().authorization())
        assertEquals(AccessIssue.PASSWORD_REQUIRED, registry.issues.value[address])
    }

    @Test
    fun `429 throttles without retry`() = runTest {
        startHttp()
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(ok())
        assertEquals(RpcResult.Failure(RpcFailure.THROTTLED), client.reboot(address))
        assertEquals(RpcResult.Failure(RpcFailure.THROTTLED), client.reboot(address))
        assertEquals(1, server.requestCount)
        assertEquals(AccessIssue.THROTTLED, registry.issues.value[address])
    }

    @Test
    fun `expired nonce is renewed once`() = runTest {
        startHttp()
        knownPassword()
        registry.setSession("http://$address", DigestSession("r", "old"))
        server.enqueue(challenge("n2"))
        server.enqueue(ok())
        val r = client.reboot(address)
        assertTrue("attendu Success, obtenu $r", r is RpcResult.Success)
        assertEquals(2, server.requestCount)
        val first = take()
        assertEquals("old", first.nonce())
        assertEquals("00000001", first.nc())
        val second = take()
        assertEquals("n2", second.nonce())
        assertEquals("00000001", second.nc())
    }

    @Test
    fun `parallel calls to a protected address are serialised`() = runTest {
        startHttp()
        knownPassword()
        server.enqueue(challenge())
        server.enqueue(ok())
        assertTrue(client.reboot(address) is RpcResult.Success)
        repeat(10) { server.enqueue(ok()) }
        val results = (1..10).map { async(Dispatchers.Default) { client.reboot(address) } }.awaitAll()
        assertTrue("résultats : $results", results.all { it is RpcResult.Success })
        assertEquals(12, server.requestCount)
        take(); take()
        val ncs = (1..10).map { take().nc() }
        assertEquals((2..11).map { String.format("%08x", it) }, ncs)
    }

    @Test
    fun `no Authorization over http to an address flagged HTTPS_REQUIRED`() = runTest {
        startHttp()
        knownPassword()
        registry.report(address, AccessIssue.HTTPS_REQUIRED)
        // Même une session HTTP antérieure ne doit pas être réutilisée en clair.
        registry.setSession("http://$address", DigestSession("r", "old"))
        server.enqueue(challenge())
        server.enqueue(ok())
        assertEquals(RpcResult.Failure(RpcFailure.HTTPS_REQUIRED), client.reboot(address))
        assertEquals(1, server.requestCount)
        assertNull(take().authorization())
    }

    @Test
    fun `https session is not reused over http after the pin is forgotten`() = runTest {
        startHttp()
        knownPassword()
        // Session établie sur la cible HTTPS (épingle depuis oubliée) : jamais réutilisée en HTTP.
        registry.setSession("https://$address", DigestSession("r", "n1"))
        server.enqueue(ok())
        assertTrue(client.reboot(address) is RpcResult.Success)
        assertNull(take().authorization())
    }

    @Test
    fun `pinned https challenge then authenticated resend succeeds`() = runTest {
        startHttps()
        knownPassword()
        registry.trustProvisionally(address, CertPins.pinOf(held.certificate))
        server.enqueue(challenge())
        server.enqueue(ok())
        val r = client.reboot(address)
        assertTrue("attendu Success, obtenu $r", r is RpcResult.Success)
        assertEquals(2, server.requestCount)
        take()
        assertEquals("00000001", take().nc())
        assertTrue(registry.sessionFor("https://$address") != null)
        assertNull(registry.sessionFor("http://$address"))
    }

    @Test
    fun `unprotected device is unchanged`() = runTest {
        startHttp()
        server.enqueue(ok())
        assertTrue(client.reboot(address) is RpcResult.Success)
        assertEquals(1, server.requestCount)
        assertNull(take().authorization())
    }
    @Test
    fun `wrong password with parallel calls makes exactly one resend`() = runTest {
        startHttp()
        knownPassword()
        repeat(10) { server.enqueue(challenge("n$it")) }
        val results = (1..5).map { async(Dispatchers.Default) { client.reboot(address) } }.awaitAll()
        assertTrue("résultats : $results", results.all { it == RpcResult.Failure(RpcFailure.PASSWORD_WRONG) })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `429 under the lock blocks queued calls`() = runTest {
        startHttp()
        knownPassword()
        server.enqueue(MockResponse.Builder().code(429).build())
        repeat(5) { server.enqueue(ok()) }
        val results = (1..5).map { async(Dispatchers.Default) { client.reboot(address) } }.awaitAll()
        assertTrue("résultats : $results", results.all { it == RpcResult.Failure(RpcFailure.THROTTLED) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 on the resend`() = runTest {
        startHttp()
        knownPassword()
        server.enqueue(challenge())
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(ok())
        assertEquals(RpcResult.Failure(RpcFailure.THROTTLED), client.reboot(address))
        assertEquals(2, server.requestCount)
        assertEquals(RpcResult.Failure(RpcFailure.THROTTLED), client.reboot(address))
        assertEquals(2, server.requestCount)
        // Un 429 ne dit rien du mot de passe : ni refusé ni accepté.
        assertEquals(PasswordState.UNVERIFIED, passwords[address]!!.state)
    }

    @Test
    fun `a 401 with an unparseable challenge never crashes`() = runTest {
        startHttp()
        // Nonce non-ASCII : OkHttp refuserait de le recopier dans un en-tête (IllegalArgumentException).
        fun bad() = MockResponse.Builder().code(401)
            .addHeaderLenient("WWW-Authenticate", "Digest qop=\"auth\", realm=\"r\", nonce=\"n\u00e9\", algorithm=SHA-256")
            .build()
        server.enqueue(bad())
        // Sans mot de passe : « mot de passe requis », comme pour un défi absent.
        assertEquals(RpcResult.Failure(RpcFailure.PASSWORD_REQUIRED), client.reboot(address))
        knownPassword()
        registry.reload()
        server.enqueue(bad())
        // Avec un mot de passe : réponse inexploitable, aucun renvoi.
        assertEquals(RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE), client.reboot(address))
        assertEquals(2, server.requestCount)
        assertNull(take().authorization())
        assertNull(take().authorization())
    }

    @Test
    fun `after an https redirect, a later plain 200 does not re-allow clear-text auth`() = runTest {
        startHttp()
        knownPassword()
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "https://127.0.0.1/rpc").build())
        assertEquals(RpcResult.Failure(RpcFailure.HTTPS_REQUIRED), client.reboot(address))
        server.enqueue(ok())
        assertTrue(client.reboot(address) is RpcResult.Success)
        assertNull(registry.issues.value[address])
        server.enqueue(challenge())
        server.enqueue(ok())
        assertEquals(RpcResult.Failure(RpcFailure.HTTPS_REQUIRED), client.reboot(address))
        assertEquals(3, server.requestCount)
        repeat(3) { assertNull(take().authorization()) }
    }

    @Test
    fun `a redirect to an authenticated request does not accept the password`() = runTest {
        startHttp()
        knownPassword()
        registry.setSession("http://$address", DigestSession("r", "n1"))
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "https://127.0.0.1/rpc").build())
        assertEquals(RpcResult.Failure(RpcFailure.HTTPS_REQUIRED), client.reboot(address))
        assertTrue(take().authorization() != null)
        assertEquals(PasswordState.UNVERIFIED, passwords[address]!!.state)
    }

    @Test
    fun `a clear-text challenge refused for https flags HTTPS_REQUIRED`() = runTest {
        startHttp()
        knownPassword()
        registry.markHttpsSeen(address)
        server.enqueue(challenge())
        assertEquals(RpcResult.Failure(RpcFailure.HTTPS_REQUIRED), client.reboot(address))
        assertEquals(AccessIssue.HTTPS_REQUIRED, registry.issues.value[address])
        assertNull(take().authorization())
    }
}
