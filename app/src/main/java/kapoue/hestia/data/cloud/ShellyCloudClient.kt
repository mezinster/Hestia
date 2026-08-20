package kapoue.hestia.data.cloud

import kapoue.hestia.core.log.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client pour l'API Cloud Control de Shelly (v2.0-beta) — utilisée **uniquement** en repli quand
 * un appareil est injoignable en local (voir CLAUDE.md : toujours opt-in, jamais la voie
 * principale). Authentification par clé opaque collée par l'utilisateur dans Réglages, jamais un
 * identifiant/mot de passe géré par Hestia.
 *
 * Limitée à 1 requête/seconde côté Shelly (validé en direct le 2026-08-20 : une extinction
 * envoyée juste après un allumage a été silencieusement ignorée) — [rateLimited] sérialise **tous**
 * les appels de ce singleton (lecture d'état groupée comme pilotage), qu'ils viennent d'un
 * rafraîchissement automatique ou d'une action bouton, pour qu'ils ne se marchent jamais dessus.
 */
@Singleton
class ShellyCloudClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val logger: DiagnosticLogger,
) {
    private val rateLimitMutex = Mutex()
    private var lastCallAtMs = 0L

    /**
     * Valide une clé + un serveur sans dépendre d'un appareil réel : identifiant bidon, seul le
     * code HTTP compte (200 = clé acceptée même si l'appareil bidon n'existe pas, 401 = clé
     * invalide) — validé en direct le 2026-08-20.
     */
    suspend fun testAuth(server: String, authKey: String): Boolean = rateLimited {
        val body = buildJsonObject { put("ids", buildJsonArray { add(PROBE_DEVICE_ID) }) }
        val request = Request.Builder()
            .url("https://$server/v2/devices/api/get?auth_key=$authKey")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                // Jamais la clé ni le serveur dans le journal, comme un mot de passe.
                logger.info(DiagnosticLogger.RPC, "Cloud.get (test clé) → ${response.code}")
                response.code == 200
            }
        } catch (e: IOException) {
            logger.warn(DiagnosticLogger.RPC, "Cloud.get (test clé) → échec réseau : ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Lit l'état (`status`) d'un ou plusieurs appareils physiques en un seul appel groupé (jusqu'à
     * 10 identifiants par appel Shelly — paginé en tranches sinon, chacune sérialisée par
     * [rateLimited] comme le reste). Ne renvoie que les appareils réellement connectés au cloud
     * (`online == 1`) : un appareil éteint côté cloud (ex. `cloud.enable` désactivé sur cet
     * appareil précis) est absent du résultat, l'appelant garde alors son échec local tel quel.
     */
    suspend fun getBatchStatus(server: String, authKey: String, deviceIds: List<String>): Map<String, JsonObject> {
        if (deviceIds.isEmpty()) return emptyMap()
        val merged = mutableMapOf<String, JsonObject>()
        for (chunk in deviceIds.distinct().chunked(MAX_IDS_PER_CALL)) {
            merged += getBatchStatusChunk(server, authKey, chunk)
        }
        return merged
    }

    private suspend fun getBatchStatusChunk(server: String, authKey: String, ids: List<String>): Map<String, JsonObject> =
        rateLimited {
            val body = buildJsonObject {
                put("ids", buildJsonArray { ids.forEach { add(it) } })
                put("select", buildJsonArray { add("status") })
            }
            val request = Request.Builder()
                .url("https://$server/v2/devices/api/get?auth_key=$authKey")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    logger.info(DiagnosticLogger.RPC, "Cloud.get (repli, ${ids.size} appareil(s)) → ${response.code}")
                    if (response.code != 200) return@use emptyMap()
                    val text = response.body?.string().orEmpty()
                    val devices = runCatching {
                        json.decodeFromString(ListSerializer(CloudDeviceState.serializer()), text)
                    }.getOrElse {
                        logger.warn(DiagnosticLogger.RPC, "Cloud.get (repli) → réponse illisible")
                        emptyList()
                    }
                    devices.filter { it.online == 1 }.mapNotNull { d -> d.status?.let { d.id to it } }.toMap()
                }
            } catch (e: IOException) {
                logger.warn(DiagnosticLogger.RPC, "Cloud.get (repli) → échec réseau : ${e.javaClass.simpleName}")
                emptyMap()
            }
        }

    /** Allume/éteint un canal via le cloud. `true` seulement si l'appareil a confirmé (200 OK). */
    suspend fun setSwitch(server: String, authKey: String, deviceId: String, channel: Int, on: Boolean): Boolean =
        rateLimited {
            val body = buildJsonObject {
                put("id", deviceId)
                put("channel", channel)
                put("on", on)
            }
            val request = Request.Builder()
                .url("https://$server/v2/devices/api/set/switch?auth_key=$authKey")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    logger.info(DiagnosticLogger.RPC, "Cloud.set/switch (repli) → ${response.code}")
                    response.code == 200
                }
            } catch (e: IOException) {
                logger.warn(DiagnosticLogger.RPC, "Cloud.set/switch (repli) → échec réseau : ${e.javaClass.simpleName}")
                false
            }
        }

    /** Espace tous les appels d'au moins [MIN_INTERVAL_MS] — un seul en vol à la fois. */
    private suspend fun <T> rateLimited(block: suspend () -> T): T = rateLimitMutex.withLock {
        val elapsed = System.currentTimeMillis() - lastCallAtMs
        if (elapsed < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - elapsed)
        withContext(Dispatchers.IO) { block() }.also { lastCallAtMs = System.currentTimeMillis() }
    }

    @Serializable
    private data class CloudDeviceState(
        val id: String,
        val online: Int = 0,
        val status: JsonObject? = null,
    )

    private companion object {
        const val PROBE_DEVICE_ID = "000000000000"
        const val MAX_IDS_PER_CALL = 10
        const val MIN_INTERVAL_MS = 1_100L
        val jsonMediaType = "application/json".toMediaType()
    }
}
