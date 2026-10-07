package kapoue.hestia.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.dao.PausedCoverEventDao
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PausedCoverEvent
import kapoue.hestia.data.local.entity.toEvent
import kapoue.hestia.data.local.entity.toPaused
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.rpc.CoverScheduleRpc
import kapoue.hestia.data.rpc.NtfyTexts
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.coverEventsFrom
import kapoue.hestia.data.rpc.coverNtfyCall
import kapoue.hestia.data.rpc.model.ScheduleJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import kapoue.hestia.domain.model.coverEventDisplayOrder
import kotlinx.coroutines.flow.Flow

/** Nombre maximal d'événements de programmation par volet (une plage en compte deux). */
internal const val MAX_COVER_EVENTS = 10

/** Résultat d'une modification de la programmation d'un volet. */
sealed interface CoverEventResult {
    data object Success : CoverEventResult
    /** Événement unique dont la date et l'heure sont déjà passées. */
    data object PastOnce : CoverEventResult
    /** Un événement occupe déjà ce créneau. */
    data object Duplicate : CoverEventResult
    /** Le volet porte déjà le maximum d'événements. */
    data object LimitReached : CoverEventResult
    data object Error : CoverEventResult
}

/**
 * Valide des événements avant création. Renvoie null s'ils sont acceptés, sinon la raison du refus.
 * Ordre des contrôles : date passée, créneau en double (avec l'existant ou entre eux), limite.
 * [ignoring] est l'événement remplacé lors d'une modification : il ne compte ni comme doublon ni dans la limite.
 */
internal fun validateNewEvents(
    existing: List<CoverEvent>,
    new: List<CoverEvent>,
    now: LocalDateTime,
    ignoring: CoverEvent? = null,
): CoverEventResult? {
    if (new.any { it.isExpiredOnce(now) }) return CoverEventResult.PastOnce
    // L'événement remplacé ne libère de la place que s'il est réellement présent.
    val others = existing.toMutableList().also { if (ignoring != null) it.remove(ignoring) }
    val duplicate = new.indices.any { i ->
        others.any { new[i].sameSlotAs(it) } || (i + 1 until new.size).any { j -> new[i].sameSlotAs(new[j]) }
    }
    if (duplicate) return CoverEventResult.Duplicate
    if (others.size + new.size > MAX_COVER_EVENTS) return CoverEventResult.LimitReached
    return null
}

/**
 * Vrai si le dernier appel de [job] (ntfy) diffère de [expected] : [expected] nul = ntfy désactivé,
 * donc tout appel `HTTP.Request` final est à retirer. Couvre aussi un changement de sujet ou de titre.
 */
internal fun coverJobNeedsNtfyRewrite(job: ScheduleJob, expected: JsonObject?): Boolean {
    val last = job.calls.drop(1).lastOrNull()?.takeIf { it.method == "HTTP.Request" }
    if (last == null) return expected != null
    if (expected == null) return true
    val actual = buildJsonObject {
        put("method", last.method)
        last.params?.let { put("params", it) }
    }
    return actual != expected
}

/** Décision de resynchronisation ntfy pour un job de volet. */
internal enum class NtfyResyncStep { Keep, Rewrite, DeleteOnly }

/**
 * Plan par id de job. Un job déjà conforme est conservé ; un job périmé dont une copie conforme
 * existe déjà (même créneau, même action, autre job) est seulement supprimé, sinon réécrit.
 * Évite d'accumuler des doublons quand la suppression d'un ancien job a échoué au passage précédent.
 * [expectedFor] donne l'appel ntfy attendu selon l'action (nul = ntfy désactivé).
 */
internal fun coverNtfyResyncPlan(
    jobs: List<ScheduleJob>,
    coverId: Int,
    expectedFor: (CoverEventAction) -> JsonObject?,
): Map<Int, NtfyResyncStep> {
    val entries = jobs.mapNotNull { job ->
        coverEventsFrom(listOf(job), coverId).firstOrNull()?.let { Triple(job, it, !coverJobNeedsNtfyRewrite(job, expectedFor(it.action))) }
    }
    return entries.associate { (job, event, conform) ->
        job.id to when {
            conform -> NtfyResyncStep.Keep
            entries.any { (other, e, ok) -> ok && other.id != job.id && e.action == event.action && e.sameSlotAs(event) } -> NtfyResyncStep.DeleteOnly
            else -> NtfyResyncStep.Rewrite
        }
    }
}

/**
 * Programmation des volets (lot S2, 2026-10-07, fork) : les événements sont des plannings natifs
 * exécutés par l'appareil ; Hestia ne les stocke pas, hormis le mémo des événements en pause.
 * Le canal cover:N est le `switchId` de l'appareil (voir [CoverRepository]).
 */
@Singleton
class CoverScheduleRepository @Inject constructor(
    private val scheduleRpc: CoverScheduleRpc,
    private val deviceRepository: DeviceRepository,
    private val pausedDao: PausedCoverEventDao,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
    @ApplicationContext private val context: Context,
) {
    private fun isDemo(device: Device) = device.ipAddress.startsWith(DeviceRepository.DEMO_IP_PREFIX)

    /** Même condition que `DeviceRepository.ntfyTopic` : ntfy activé ET un sujet existe. */
    private fun ntfyTexts(device: Device, action: CoverEventAction): NtfyTexts? {
        val topic = (if (appPreferences.ntfyEnabled.value) appPreferences.ntfyTopic.value else null) ?: return null
        val body = when (action) {
            CoverEventAction.Open -> context.getString(R.string.cover_notif_opened)
            CoverEventAction.Close -> context.getString(R.string.cover_notif_closed)
            is CoverEventAction.GoTo -> context.getString(R.string.cover_notif_moved, action.position)
        }
        return NtfyTexts(topic = topic, title = device.name, body = body)
    }

    fun observePaused(deviceId: Long): Flow<List<PausedCoverEvent>> = pausedDao.observeForDevice(deviceId)

    /**
     * Relit les événements du volet. Chaque lecture (affichage, validation avant création,
     * resynchronisation) purge au passage les uniques échus, journalisé : ils ne s'exécuteront plus.
     */
    private suspend fun readEvents(ip: String, device: Device): RpcResult<List<CoverEvent>> {
        val jobs = when (val r = scheduleRpc.list(ip)) {
            is RpcResult.Success -> r.value.jobs
            is RpcResult.RpcError -> return r
            is RpcResult.Failure -> return r
        }
        val all = coverEventsFrom(jobs, device.switchId)
        val now = LocalDateTime.now()
        val (expired, alive) = all.partition { it.isExpiredOnce(now) }
        for (e in expired) {
            val id = e.jobId ?: continue
            val ok = scheduleRpc.delete(ip, id) is RpcResult.Success
            logger.info(DiagnosticLogger.RPC, "Volet ${device.id} : événement unique échu purgé (job $id, ${if (ok) "ok" else "échec"})")
        }
        return RpcResult.Success(alive.sortedWith(coverEventDisplayOrder))
    }

    suspend fun getEvents(device: Device): RpcResult<List<CoverEvent>> {
        if (isDemo(device)) return RpcResult.Success(listOf(CoverEvent(21, 0, action = CoverEventAction.Close)))
        return deviceRepository.withIp(device) { ip -> readEvents(ip, device) }.second
    }

    private fun fail(device: Device, what: String, r: RpcResult<*>): CoverEventResult {
        when (r) {
            is RpcResult.RpcError -> logger.warn(DiagnosticLogger.RPC, "Volet ${device.id} : $what refusé par l'appareil (code ${r.code})")
            is RpcResult.Failure -> logger.warn(DiagnosticLogger.RPC, "Volet ${device.id} : $what impossible (appareil injoignable)")
            is RpcResult.Success -> Unit
        }
        return CoverEventResult.Error
    }

    /**
     * Lit l'existant (seul appel passant par la bascule entre adresses IP), puis exécute [block] sur
     * l'IP qui a répondu, sans relance sur la seconde adresse : un échec en cours de séquence donne
     * [CoverEventResult.Error], jamais un faux doublon.
     */
    private suspend fun run(
        device: Device,
        what: String,
        block: suspend (ip: String, existing: List<CoverEvent>) -> CoverEventResult,
    ): CoverEventResult {
        if (isDemo(device)) return CoverEventResult.Success
        val (ip, read) = deviceRepository.withIp(device) { ip -> readEvents(ip, device) }
        return when (read) {
            is RpcResult.Success -> block(ip, read.value)
            else -> fail(device, what, read)
        }
    }

    /** Crée un événement (ntfy en dernier appel s'il est actif) et renvoie l'id du job créé. */
    private suspend fun create(ip: String, device: Device, event: CoverEvent): RpcResult<Int> =
        when (val r = scheduleRpc.create(ip, event, device.switchId, ntfyTexts(device, event.action))) {
            is RpcResult.Success -> RpcResult.Success(r.value.id)
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }

    suspend fun add(device: Device, event: CoverEvent): CoverEventResult = run(device, "ajout d'événement") { ip, existing ->
        validateNewEvents(existing, listOf(event), LocalDateTime.now())?.let { return@run it }
        val c = create(ip, device, event)
        if (c is RpcResult.Success) CoverEventResult.Success else fail(device, "ajout d'événement", c)
    }

    /** Crée [first] puis [second] ; si [second] échoue, [first] est supprimé (plage tout ou rien). */
    suspend fun addWindow(device: Device, first: CoverEvent, second: CoverEvent): CoverEventResult =
        run(device, "ajout de plage") { ip, existing ->
            validateNewEvents(existing, listOf(first, second), LocalDateTime.now())?.let { return@run it }
            val firstId = when (val c = create(ip, device, first)) {
                is RpcResult.Success -> c.value
                else -> return@run fail(device, "ajout de plage", c)
            }
            val c = create(ip, device, second)
            if (c is RpcResult.Success) return@run CoverEventResult.Success
            if (scheduleRpc.delete(ip, firstId) !is RpcResult.Success) {
                logger.warn(DiagnosticLogger.RPC, "Volet ${device.id} : retrait de la première moitié de la plage impossible (job $firstId), doublon possible")
            }
            fail(device, "ajout de plage", c)
        }

    /**
     * Crée le nouvel événement puis supprime l'ancien : un échec ne perd jamais l'existant. Si la
     * création a réussi, c'est un succès même quand la suppression échoue (comme `updatePlanning`).
     */
    suspend fun update(device: Device, old: CoverEvent, new: CoverEvent): CoverEventResult =
        run(device, "modification d'événement") { ip, existing ->
            val oldId = old.jobId ?: return@run CoverEventResult.Error
            validateNewEvents(existing, listOf(new), LocalDateTime.now(), ignoring = old)?.let { return@run it }
            val c = create(ip, device, new)
            if (c !is RpcResult.Success) return@run fail(device, "modification d'événement", c)
            if (scheduleRpc.delete(ip, oldId) !is RpcResult.Success) {
                logger.warn(DiagnosticLogger.RPC, "Volet ${device.id} : ancien événement non supprimé (job $oldId), doublon possible")
            }
            CoverEventResult.Success
        }

    suspend fun delete(device: Device, event: CoverEvent): CoverEventResult {
        if (isDemo(device)) return CoverEventResult.Success
        val id = event.jobId ?: return CoverEventResult.Error
        // Un seul appel : la bascule entre adresses est sans risque ici.
        val r = deviceRepository.withIp(device) { ip -> scheduleRpc.delete(ip, id) }.second
        return if (r is RpcResult.Success) CoverEventResult.Success else fail(device, "suppression d'événement", r)
    }

    /** Supprime le job de l'appareil, puis mémorise l'événement localement (seulement si succès). */
    suspend fun pause(device: Device, event: CoverEvent): CoverEventResult {
        val result = delete(device, event)
        if (result == CoverEventResult.Success && !isDemo(device)) {
            pausedDao.insert(event.toPaused(device.id, System.currentTimeMillis()))
        }
        return result
    }

    /** Recrée l'événement en pause ; la ligne locale n'est effacée qu'en cas de succès. */
    suspend fun resume(device: Device, paused: PausedCoverEvent): CoverEventResult {
        val event = runCatching { paused.toEvent() }.getOrElse {
            logger.warn(DiagnosticLogger.DB, "Volet ${device.id} : événement en pause illisible (ligne ${paused.id})")
            return CoverEventResult.Error
        }
        val result = add(device, event)
        if (result == CoverEventResult.Success) pausedDao.delete(paused)
        return result
    }

    /**
     * Réécrit avec ou sans ntfy, selon les réglages actuels, les seuls événements dont l'appel ntfy
     * final diffère de l'attendu (idempotent : un nouveau passage ne réécrit rien). Création du
     * remplaçant d'abord, suppression de l'ancien seulement après succès (jamais d'événement perdu).
     * @return faux au moindre échec (lecture, création ou suppression), pour que le rattrapage réessaie.
     */
    suspend fun resyncNtfy(device: Device): Boolean {
        if (isDemo(device)) return true
        val (ip, listed) = deviceRepository.withIp(device) { ip -> scheduleRpc.list(ip) }
        val jobs = when (listed) {
            is RpcResult.Success -> listed.value.jobs
            else -> { fail(device, "resynchronisation ntfy (lecture)", listed); return false }
        }
        val now = LocalDateTime.now()
        val plan = coverNtfyResyncPlan(jobs, device.switchId) { action -> ntfyTexts(device, action)?.let { coverNtfyCall(it) } }
        var ok = true
        for (job in jobs) {
            val step = plan[job.id] ?: continue
            if (step == NtfyResyncStep.Keep) continue
            val event = coverEventsFrom(listOf(job), device.switchId).first()
            if (event.isExpiredOnce(now)) continue
            if (step == NtfyResyncStep.Rewrite) {
                val c = create(ip, device, event)
                if (c !is RpcResult.Success) { fail(device, "resynchronisation ntfy (création)", c); ok = false; continue }
            }
            val d = scheduleRpc.delete(ip, job.id)
            if (d !is RpcResult.Success) {
                fail(device, "resynchronisation ntfy (suppression de l'ancien job ${job.id})", d)
                ok = false
            }
        }
        return ok
    }
}
