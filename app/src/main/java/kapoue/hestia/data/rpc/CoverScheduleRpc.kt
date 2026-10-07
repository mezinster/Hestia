package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.ScheduleCreateResult
import kapoue.hestia.data.rpc.model.ScheduleDeleteResult
import kapoue.hestia.data.rpc.model.ScheduleJob
import kapoue.hestia.data.rpc.model.ScheduleListResult
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Textes d'une notification ntfy attachée à un événement de volet (envoyée par l'appareil). */
data class NtfyTexts(val topic: String, val title: String, val body: String)

/** Appel RPC exécuté par la tâche : ouverture, fermeture ou déplacement du volet [coverId]. */
internal fun coverActionCall(coverId: Int, action: CoverEventAction): JsonObject = buildJsonObject {
    when (action) {
        CoverEventAction.Open -> {
            put("method", "Cover.Open")
            put("params", buildJsonObject { put("id", coverId) })
        }
        CoverEventAction.Close -> {
            put("method", "Cover.Close")
            put("params", buildJsonObject { put("id", coverId) })
        }
        is CoverEventAction.GoTo -> {
            put("method", "Cover.GoToPosition")
            put(
                "params",
                buildJsonObject {
                    put("id", coverId)
                    put("pos", action.position)
                },
            )
        }
    }
}

/**
 * Appel `HTTP.Request` (POST vers ntfy), de même forme que celui des plannings relais.
 * Reproduit ici car l'original est privé à [ShellyRpcClient] ; timeout court (5 s) pour ne
 * jamais retarder l'action réelle si ntfy.sh est lent ou injoignable.
 */
internal fun coverNtfyCall(ntfy: NtfyTexts): JsonObject = buildJsonObject {
    put("method", "HTTP.Request")
    put(
        "params",
        buildJsonObject {
            put("method", "POST")
            put("url", "https://ntfy.sh/${ntfy.topic}")
            put("body", ntfy.body)
            put("timeout", 5)
            put("headers", buildJsonObject { put("Title", ntfy.title) })
        },
    )
}

/** Paramètres de `Schedule.Create` : l'action du volet, puis ntfy en **dernier** appel s'il est fourni. */
internal fun coverScheduleCreateParams(
    timespec: String,
    coverId: Int,
    action: CoverEventAction,
    ntfy: NtfyTexts?,
): JsonObject = buildJsonObject {
    put("enable", true)
    put("timespec", timespec)
    put(
        "calls",
        buildJsonArray {
            add(coverActionCall(coverId, action))
            if (ntfy != null) add(coverNtfyCall(ntfy))
        },
    )
}

/** timespec d'un événement : date précise (unique) ou jours de la semaine (récurrent). */
internal fun coverTimespec(event: CoverEvent): String {
    val date = event.date
    return if (date != null) {
        ScheduleCodec.timespecOnce(event.hour, event.minute, date)
    } else {
        ScheduleCodec.timespec(event.hour, event.minute, event.days)
    }
}

/**
 * Relit les événements du volet [coverId] depuis les plannings de l'appareil. Seul le premier
 * appel d'un job compte (les suivants, ntfy ou autre tâche, sont ignorés). Un `GoToPosition`
 * sans `pos` exploitable ou un timespec illisible écartent le job.
 */
internal fun coverEventsFrom(jobs: List<ScheduleJob>, coverId: Int): List<CoverEvent> =
    jobs.mapNotNull { job ->
        val call = job.calls.firstOrNull() ?: return@mapNotNull null
        val params = call.params ?: return@mapNotNull null
        if ((params["id"] as? JsonPrimitive)?.intOrNull != coverId) return@mapNotNull null
        val action = when (call.method) {
            "Cover.Open" -> CoverEventAction.Open
            "Cover.Close" -> CoverEventAction.Close
            "Cover.GoToPosition" -> {
                val pos = (params["pos"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..100 } ?: return@mapNotNull null
                CoverEventAction.GoTo(pos)
            }
            else -> return@mapNotNull null
        }
        val parsed = ScheduleCodec.parse(job.timespec) ?: return@mapNotNull null
        // Tâche créée hors Hestia avec des valeurs hors limites : l'ignorer (LocalTime.of lèverait).
        if (parsed.hour !in 0..23 || parsed.minute !in 0..59 || parsed.days.any { it !in 0..6 }) return@mapNotNull null
        // Convention de CoverEvent : « tous les jours » = jours vides (le cron `*` est décodé en 0..6)
        val days = if (parsed.date == null && parsed.days.size >= 7) emptySet() else parsed.days
        CoverEvent(parsed.hour, parsed.minute, days, parsed.date, action, job.id)
    }

/** Réponse de `Schedule.Update` (on n'exploite que la présence d'un résultat). */
@Serializable
data class ScheduleUpdateResult(val rev: Int? = null)

/**
 * Paramètres de `Schedule.Update` : remplace seulement la liste des appels du job [id], en place.
 * Ni `enable` ni `timespec` ne sont envoyés, l'appareil les conserve tels quels.
 */
internal fun coverScheduleUpdateParams(id: Int, calls: List<JsonObject>): JsonObject = buildJsonObject {
    put("id", id)
    put("calls", buildJsonArray { calls.forEach { add(it) } })
}

/** Programmation des volets : création, relecture, mise à jour et suppression des plannings natifs de l'appareil. */
@Singleton
class CoverScheduleRpc @Inject constructor(private val rpc: ShellyRpcClient) {

    suspend fun create(
        ip: String,
        event: CoverEvent,
        coverId: Int,
        ntfy: NtfyTexts?,
    ): RpcResult<ScheduleCreateResult> = rpc.call(
        ip,
        "Schedule.Create",
        coverScheduleCreateParams(coverTimespec(event), coverId, event.action, ntfy),
        ScheduleCreateResult.serializer(),
    )

    /** Réécrit en place les appels du job [id] (même id, même activation, même horaire). */
    suspend fun update(ip: String, id: Int, calls: List<JsonObject>): RpcResult<ScheduleUpdateResult> = rpc.call(
        ip,
        "Schedule.Update",
        coverScheduleUpdateParams(id, calls),
        ScheduleUpdateResult.serializer(),
    )

    suspend fun list(ip: String): RpcResult<ScheduleListResult> = rpc.scheduleList(ip)

    suspend fun delete(ip: String, id: Int): RpcResult<ScheduleDeleteResult> = rpc.scheduleDelete(ip, id)
}
