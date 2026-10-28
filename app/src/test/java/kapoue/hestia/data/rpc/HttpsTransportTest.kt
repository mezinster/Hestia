package kapoue.hestia.data.rpc

import kapoue.hestia.core.log.DiagnosticLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Transport HTTPS épinglé (TOFU) : détection d'un appareil en HTTPS seul, appel épinglé,
 * refus d'un certificat changé, jamais de repli en HTTP, lecture de certificat sans confiance.
 */
class HttpsTransportTest {

    /** Lignes du journal de diagnostic, pour vérifier le protocole tracé (revue finale C1, F3). */
    private val logLines = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val logger = object : DiagnosticLogger {
        override fun info(tag: String, message: String) { logLines += message }
        override fun warn(tag: String, message: String) { logLines += message }
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private val registry = DeviceAccessRegistry(SavedPinSource { emptyMap() })
    private val client = clientWith(registry)

    private fun clientWith(registry: DeviceAccessRegistry) = ShellyRpcClient(
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

    private fun ok() = MockResponse.Builder().code(200).body("""{"id":1,"result":{}}""").build()

    @Test
    fun `redirect to https is reported and not followed`() = runTest {
        startHttp()
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "https://127.0.0.1/rpc").build())
        val r = client.reboot(address)
        assertEquals(RpcResult.Failure(RpcFailure.HTTPS_REQUIRED), r)
        assertEquals(1, server.requestCount)
        assertEquals(AccessIssue.HTTPS_REQUIRED, registry.issues.value[address])
    }

    @Test
    fun `redirect elsewhere is a malformed response`() = runTest {
        startHttp()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://example.invalid/").build())
        val r = client.reboot(address)
        assertEquals(RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE), r)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `pinned https call succeeds`() = runTest {
        startHttps()
        registry.trustProvisionally(address, CertPins.pinOf(held.certificate))
        server.enqueue(ok())
        val r = client.reboot(address)
        assertTrue("attendu Success, obtenu $r", r is RpcResult.Success)
        assertEquals("/rpc", server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test
    fun `another certificate is refused as changed`() = runTest {
        startHttps()
        val other = HeldCertificate.Builder().commonName("other").build()
        registry.trustProvisionally(address, CertPins.pinOf(other.certificate))
        server.enqueue(ok())
        val r = client.reboot(address)
        assertEquals(RpcResult.Failure(RpcFailure.CERT_CHANGED), r)
        assertEquals(AccessIssue.CERT_CHANGED, registry.issues.value[address])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `pinned address never falls back to http`() = runTest {
        startHttp()
        registry.trustProvisionally(address, CertPins.pinOf(held.certificate))
        server.enqueue(ok())
        val r = client.reboot(address)
        assertTrue("aucun succès attendu, obtenu $r", r !is RpcResult.Success)
        // L'échec est tracé comme un appel HTTPS, jamais HTTP.
        assertTrue("journal : $logLines", logLines.any { it.contains("@ https://$address → ") })
        assertTrue("journal : $logLines", logLines.none { it.contains("http://") })
        // MockWebServer compte comme « requête » ratée les octets TLS illisibles qu'il reçoit :
        // on vérifie donc qu'aucune n'est une vraie requête HTTP (toutes en échec d'analyse).
        repeat(server.requestCount) {
            val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue("requête HTTP reçue en clair : ${recorded.requestLine}", recorded.failure != null)
            assertNotEquals("POST", recorded.method)
        }
    }

    @Test
    fun `a successful answer clears the issue`() = runTest {
        startHttp()
        registry.report(address, AccessIssue.HTTPS_REQUIRED)
        server.enqueue(ok())
        val r = client.reboot(address)
        assertTrue("attendu Success, obtenu $r", r is RpcResult.Success)
        assertTrue(registry.issues.value.isEmpty())
        assertTrue("journal : $logLines", logLines.any { it.contains("@ http://$address → 200") })
    }

    @Test
    fun `peek reads the certificate without trusting it`() = runTest {
        startHttps()
        val r = client.peekCertificate(address)
        assertEquals(
            RpcResult.Success(
                PeekedCertificate(
                    pin = CertPins.pinOf(held.certificate),
                    fingerprint = CertPins.fingerprintOf(held.certificate),
                ),
            ),
            r,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `peek on a closed port fails`() = runTest {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val r = client.peekCertificate("127.0.0.1:$closedPort")
        assertEquals(RpcResult.Failure(RpcFailure.UNREACHABLE), r)
        assertNotEquals(0, closedPort)
    }

    @Test
    fun `a call before any sync still uses the saved pin`() = runTest {
        startHttps()
        val pin = CertPins.pinOf(held.certificate)
        // Démarrage à froid : aucune synchronisation, seule la base connaît l'épingle.
        val cold = clientWith(DeviceAccessRegistry(SavedPinSource { mapOf(address to pin) }))
        server.enqueue(ok())
        val r = cold.reboot(address)
        assertTrue("attendu Success, obtenu $r", r is RpcResult.Success)
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("https", recorded.url.scheme)
        assertTrue("journal : $logLines", logLines.any { it.contains("@ https://$address → 200") })
    }

    @Test
    fun `unreadable pins refuse the call`() = runTest {
        startHttp()
        val blind = clientWith(DeviceAccessRegistry(SavedPinSource { throw IllegalStateException("base illisible") }))
        server.enqueue(ok())
        val r = blind.reboot(address)
        assertEquals(RpcResult.Failure(RpcFailure.UNREACHABLE), r)
        assertEquals(0, server.requestCount)
    }
}
