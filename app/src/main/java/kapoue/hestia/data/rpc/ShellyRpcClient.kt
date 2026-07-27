package kapoue.hestia.data.rpc

import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.rpc.model.CheckForUpdateResult
import kapoue.hestia.data.rpc.model.ComponentsResult
import kapoue.hestia.data.rpc.model.DeviceInfoResult
import kapoue.hestia.data.rpc.model.RpcEnvelope
import kapoue.hestia.data.rpc.model.RpcRequest
import kapoue.hestia.data.rpc.model.ScheduleCreateResult
import kapoue.hestia.data.rpc.model.ScheduleDeleteResult
import kapoue.hestia.data.rpc.model.ScheduleListResult
import kapoue.hestia.data.rpc.model.ScriptCreateResult
import kapoue.hestia.data.rpc.model.ScriptGetCodeResult
import kapoue.hestia.data.rpc.model.ScriptListResult
import kapoue.hestia.data.rpc.model.ScriptPutCodeResult
import kapoue.hestia.data.rpc.model.ScriptRunResult
import kapoue.hestia.data.rpc.model.SetConfigResult
import kapoue.hestia.data.rpc.model.ShellyFullStatus
import kapoue.hestia.data.rpc.model.SwitchSetResult
import kapoue.hestia.data.rpc.model.SwitchStatusResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client JSON-RPC 2.0 pour les appareils Shelly Gen2+ (SPEC-V1 § 3).
 *
 * Principes :
 * - communication HTTP locale uniquement, vers l'adresse fournie par l'appelant ;
 * - timeout court, **aucun retour automatique** (le réessai est déclenché par l'utilisateur) ;
 * - aucune chaîne utilisateur produite ici : les erreurs sont typées ([RpcResult]) ;
 * - chaque appel est journalisé (méthode, cible, code, durée).
 */
@Singleton
class ShellyRpcClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val logger: DiagnosticLogger,
) {
    private val requestIds = java.util.concurrent.atomic.AtomicInteger(1)
    private val jsonMediaType = "application/json".toMediaType()

    /** État complet d'un canal switch. */
    suspend fun getSwitchStatus(ip: String, switchId: Int): RpcResult<SwitchStatusResult> =
        call(ip, "Switch.GetStatus", buildJsonObject { put("id", switchId) }, SwitchStatusResult.serializer())

    /**
     * Allume ou éteint un canal switch.
     *
     * [toggleAfterSec] arme un minuteur **one-shot** tenu par l'appareil : il rebascule le canal
     * après ce délai, puis oublie. C'est un compte à rebours volatil, il n'écrit **rien** dans la
     * configuration de l'appareil — contrairement à `auto_off`, qui est une configuration
     * persistante et s'appliquerait dès lors à *tous* les allumages suivants, y compris ceux
     * déclenchés par le bouton physique ou l'interface web native.
     *
     * L'appareil renseigne quand même `timer_started_at` / `timer_duration` dans Switch.GetStatus,
     * donc le compte à rebours reste lisible exactement comme avec auto_off.
     */
    suspend fun setSwitch(
        ip: String,
        switchId: Int,
        on: Boolean,
        toggleAfterSec: Int? = null,
    ): RpcResult<SwitchSetResult> =
        call(
            ip,
            "Switch.Set",
            buildJsonObject {
                put("id", switchId)
                put("on", on)
                if (toggleAfterSec != null) put("toggle_after", toggleAfterSec)
            },
            SwitchSetResult.serializer(),
        )

    /**
     * Désarme le minuteur `auto_off` d'un canal.
     *
     * Hestia n'arme **jamais** `auto_off` : ses minuteurs passent par `toggle_after` (voir
     * [setSwitch]). Cet appel ne sert qu'à neutraliser une configuration posée **hors** de
     * l'application (interface web native, version antérieure), qui entrerait en conflit avec la
     * simulation de présence. Il n'existe volontairement aucun moyen d'armer `auto_off` depuis
     * ce client : c'est ce qui a causé le bug « l'interrupteur lance un minuteur ».
     */
    suspend fun clearAutoOff(ip: String, switchId: Int): RpcResult<SetConfigResult> = call(
        ip,
        "Switch.SetConfig",
        buildJsonObject {
            put("id", switchId)
            put(
                "config",
                buildJsonObject {
                    put("auto_off", false)
                },
            )
        },
        SetConfigResult.serializer(),
    )

    /** Infos appareil : modèle, génération, firmware. */
    suspend fun getDeviceInfo(ip: String): RpcResult<DeviceInfoResult> =
        call(ip, "Shelly.GetDeviceInfo", null, DeviceInfoResult.serializer())

    /**
     * Interroge les serveurs Shelly pour savoir si une mise à jour de firmware est disponible.
     * Seul appel RPC du projet qui fait sortir l'appareil du réseau local — **exclusivement à la
     * demande explicite de l'utilisateur** (bouton « Vérifier »), jamais en tâche de fond.
     */
    suspend fun checkForUpdate(ip: String): RpcResult<CheckForUpdateResult> =
        call(ip, "Shelly.CheckForUpdate", null, CheckForUpdateResult.serializer())

    /** Installe la mise à jour du canal [stage] ; l'appareil redémarre une fois l'installation faite. */
    suspend fun updateFirmware(ip: String, stage: String = "stable"): RpcResult<SetConfigResult> =
        call(ip, "Shelly.Update", buildJsonObject { put("stage", stage) }, SetConfigResult.serializer())

    /** Redémarre l'appareil (dépannage). Toujours à la demande explicite, jamais automatique. */
    suspend fun reboot(ip: String): RpcResult<SetConfigResult> =
        call(ip, "Shelly.Reboot", null, SetConfigResult.serializer())

    /** État complet de l'appareil ; on n'exploite que la section `sys` (horloge). */
    suspend fun getFullStatus(ip: String): RpcResult<ShellyFullStatus> =
        call(ip, "Shelly.GetStatus", null, ShellyFullStatus.serializer())

    // --- Scripting (simulation de présence) ---

    suspend fun scriptList(ip: String): RpcResult<ScriptListResult> =
        call(ip, "Script.List", null, ScriptListResult.serializer())

    suspend fun scriptCreate(ip: String, name: String): RpcResult<ScriptCreateResult> =
        call(ip, "Script.Create", buildJsonObject { put("name", name) }, ScriptCreateResult.serializer())

    suspend fun scriptGetCode(ip: String, id: Int): RpcResult<ScriptGetCodeResult> =
        call(ip, "Script.GetCode", buildJsonObject { put("id", id) }, ScriptGetCodeResult.serializer())

    suspend fun scriptPutCode(ip: String, id: Int, code: String): RpcResult<ScriptPutCodeResult> =
        call(
            ip,
            "Script.PutCode",
            buildJsonObject {
                put("id", id)
                put("code", code)
                put("append", false)
            },
            ScriptPutCodeResult.serializer(),
        )

    suspend fun scriptSetConfig(ip: String, id: Int, enable: Boolean): RpcResult<SetConfigResult> =
        call(
            ip,
            "Script.SetConfig",
            buildJsonObject {
                put("id", id)
                put("config", buildJsonObject { put("enable", enable) })
            },
            SetConfigResult.serializer(),
        )

    suspend fun scriptStart(ip: String, id: Int): RpcResult<ScriptRunResult> =
        call(ip, "Script.Start", buildJsonObject { put("id", id) }, ScriptRunResult.serializer())

    suspend fun scriptStop(ip: String, id: Int): RpcResult<ScriptRunResult> =
        call(ip, "Script.Stop", buildJsonObject { put("id", id) }, ScriptRunResult.serializer())

    suspend fun scriptDelete(ip: String, id: Int): RpcResult<ScriptRunResult> =
        call(ip, "Script.Delete", buildJsonObject { put("id", id) }, ScriptRunResult.serializer())

    // --- Schedule (planning natif de l'appareil) ---

    suspend fun scheduleList(ip: String): RpcResult<ScheduleListResult> =
        call(ip, "Schedule.List", null, ScheduleListResult.serializer())

    /**
     * Crée un programme cron : à [timespec], bascule le canal [switchId] sur [on]. Un seul appel
     * Switch.Set par programme — c'est ce qui permet de reconstruire les plannings côté repository.
     */
    suspend fun scheduleCreate(
        ip: String,
        timespec: String,
        switchId: Int,
        on: Boolean,
        enable: Boolean = true,
    ): RpcResult<ScheduleCreateResult> = call(
        ip,
        "Schedule.Create",
        buildJsonObject {
            put("enable", enable)
            put("timespec", timespec)
            put(
                "calls",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("method", "Switch.Set")
                            put(
                                "params",
                                buildJsonObject {
                                    put("id", switchId)
                                    put("on", on)
                                },
                            )
                        },
                    )
                },
            )
        },
        ScheduleCreateResult.serializer(),
    )

    suspend fun scheduleDelete(ip: String, id: Int): RpcResult<ScheduleDeleteResult> =
        call(ip, "Schedule.Delete", buildJsonObject { put("id", id) }, ScheduleDeleteResult.serializer())

    /**
     * Interroge l'appareil pour déduire ses capacités. Rejette les Gen1.
     * Combine Shelly.GetDeviceInfo (génération, modèle) et Shelly.GetComponents (canaux).
     */
    suspend fun probe(ip: String): RpcResult<DeviceCapabilities> {
        val info = when (val r = getDeviceInfo(ip)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return r
            is RpcResult.Failure -> return r
        }

        val generation = info.gen
        // gen absent ou < 2 ⇒ Gen1 (API non RPC) ou appareil non-Shelly : refus explicite.
        if (generation == null) return RpcResult.Failure(RpcFailure.NOT_SHELLY_GEN2)
        if (generation < 2) return RpcResult.Failure(RpcFailure.GEN1_UNSUPPORTED)

        val components = when (val r = getAllComponents(ip)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return r
            is RpcResult.Failure -> return r
        }

        val switchChannels = components
            .mapNotNull { entry -> SWITCH_KEY.matchEntire(entry.key)?.groupValues?.get(1)?.toIntOrNull() }
            .sorted()

        val hasPowerMetering = components.any { entry ->
            entry.key.startsWith("pm") || entry.key.startsWith("em") ||
                entry.status?.containsKey("apower") == true
        }

        return RpcResult.Success(
            DeviceCapabilities(
                generation = generation,
                model = info.model,
                reportedName = info.name,
                switchChannels = switchChannels,
                // Le moteur de scripts est standard sur Gen2+.
                hasScripting = generation >= 2,
                hasPowerMetering = hasPowerMetering,
            ),
        )
    }

    /** Récupère tous les composants en paginant via `offset` jusqu'à `total` (borné). */
    private suspend fun getAllComponents(ip: String): RpcResult<List<kapoue.hestia.data.rpc.model.ComponentEntry>> {
        val collected = mutableListOf<kapoue.hestia.data.rpc.model.ComponentEntry>()
        var offset = 0
        var total = Int.MAX_VALUE
        var guard = 0
        while (collected.size < total && guard < MAX_COMPONENT_PAGES) {
            guard++
            val params = buildJsonObject {
                put("offset", offset)
                put("include", buildJsonArray { add("status") })
            }
            when (val r = call(ip, "Shelly.GetComponents", params, ComponentsResult.serializer())) {
                is RpcResult.Success -> {
                    total = r.value.total.coerceAtLeast(r.value.components.size)
                    if (r.value.components.isEmpty()) break
                    collected += r.value.components
                    offset += r.value.components.size
                }
                is RpcResult.RpcError -> return r
                is RpcResult.Failure -> return r
            }
        }
        return RpcResult.Success(collected)
    }

    /** Appel générique typé. Journalise méthode, cible, code et durée. */
    private suspend fun <T> call(
        ip: String,
        method: String,
        params: JsonElement?,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): RpcResult<T> = withContext(Dispatchers.IO) {
        val id = requestIds.getAndIncrement()
        val payload = json.encodeToString(RpcRequest.serializer(), RpcRequest(id, method, params))
        val request = Request.Builder()
            .url("http://$ip/rpc")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val startedAt = System.currentTimeMillis()
        try {
            httpClient.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startedAt
                val body = response.body?.string().orEmpty()
                val envelope = runCatching { json.decodeFromString(RpcEnvelope.serializer(), body) }
                    .getOrElse {
                        logger.warn(DiagnosticLogger.RPC, "$method @ $ip → réponse illisible (${elapsed}ms)")
                        return@withContext RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE)
                    }

                envelope.error?.let { err ->
                    logger.warn(DiagnosticLogger.RPC, "$method @ $ip → erreur ${err.code} (${elapsed}ms)")
                    return@withContext RpcResult.RpcError(err.code, err.message)
                }

                // Certaines méthodes d'action (Script.Delete, etc.) renvoient un résultat null :
                // on le traite comme un objet vide, décodé vers les valeurs par défaut du type.
                val result = envelope.result ?: JsonObject(emptyMap())

                val decoded = runCatching { json.decodeFromJsonElement(serializer, result) }
                    .getOrElse {
                        logger.warn(DiagnosticLogger.RPC, "$method @ $ip → résultat inattendu (${elapsed}ms)")
                        return@withContext RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE)
                    }

                logger.info(DiagnosticLogger.RPC, "$method @ $ip → ${response.code} (${elapsed}ms)")
                RpcResult.Success(decoded)
            }
        } catch (e: SocketTimeoutException) {
            logger.warn(DiagnosticLogger.RPC, "$method @ $ip → timeout")
            RpcResult.Failure(RpcFailure.TIMEOUT)
        } catch (e: java.io.InterruptedIOException) {
            // callTimeout global d'OkHttp.
            logger.warn(DiagnosticLogger.RPC, "$method @ $ip → timeout (callTimeout)")
            RpcResult.Failure(RpcFailure.TIMEOUT)
        } catch (e: ConnectException) {
            logger.warn(DiagnosticLogger.RPC, "$method @ $ip → injoignable")
            RpcResult.Failure(RpcFailure.UNREACHABLE)
        } catch (e: IOException) {
            logger.warn(DiagnosticLogger.RPC, "$method @ $ip → échec réseau : ${e.javaClass.simpleName}")
            RpcResult.Failure(RpcFailure.UNREACHABLE)
        }
    }

    private companion object {
        val SWITCH_KEY = Regex("""switch:(\d+)""")
        const val MAX_COMPONENT_PAGES = 32
    }
}
