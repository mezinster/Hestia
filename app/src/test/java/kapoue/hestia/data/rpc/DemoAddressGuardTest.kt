package kapoue.hestia.data.rpc

import kapoue.hestia.core.log.DiagnosticLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoAddressGuardTest {

    private val sent = mutableListOf<String>()

    // Répond comme un appareil réel, pour distinguer « appel bloqué » de « appareil injoignable ».
    private val http = OkHttpClient.Builder()
        .addInterceptor { chain ->
            sent += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"id":1,"result":{}}""".toResponseBody())
                .build()
        }
        .build()

    private val logger = object : DiagnosticLogger {
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private val client = ShellyRpcClient(http, Json { ignoreUnknownKeys = true }, logger, DeviceAccessRegistry(SavedPinSource { emptyMap() }))

    @Test
    fun `no request leaves for a demo address`() = runTest {
        val r = client.reboot("203.0.113.10")
        assertTrue(sent.isEmpty())
        assertEquals(RpcResult.Failure(RpcFailure.UNREACHABLE), r)
    }

    @Test
    fun `a real address is still called`() = runTest {
        client.reboot("192.168.1.96")
        assertEquals(listOf("http://192.168.1.96/rpc"), sent)
    }
}
