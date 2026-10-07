package kapoue.hestia.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
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
import kapoue.hestia.data.rpc.model.ScheduleCall
import kapoue.hestia.data.rpc.model.ScheduleJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import kapoue.hestia.domain.model.coverEventDisplayOrder
import kapoue.hestia.domain.model.splitForRead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/** Un appel compte comme ntfy seulement s'il s'agit d'un `HTTP.Request` vers `https://ntfy.sh/`. */
internal fun isCoverNtfyCall(call: ScheduleCall): Boolean =
    call.method == "HTTP.Request" &&
        (call.params?.get("url") as? JsonPrimitive)?.contentOrNull?.startsWith("https://ntfy.sh/") == true

private fun ScheduleCall.toJson(): JsonObject = buildJsonObject {
    put("method", method)
    params?.let { put("params", it) }
}

/**
 * Appels attendus de [job] après resynchronisation ntfy : tous ses appels sauf ntfy, dans le même
 * ordre (action du volet, webhooks tiers…), puis [expected] en dernier s'il est fourni (nul = ntfy
 * désactivé). Renvoie null quand le job est déjà conforme (rien à envoyer).
 */
internal fun coverResyncCalls(job: ScheduleJob, expected: JsonObject?): List<JsonObject>? {
    val current = job.calls.map { it.toJson() }
    val next = job.calls.filterNot(::isCoverNtfyCall).map { it.toJson() } + listOfNotNull(expected)
    return next.takeIf { it != current }
}

/**
 * Un verrou par appareil : les opérations de programmation d'un même volet (lecture puis
 * création/suppression/mise à jour) ne s'entrelacent jamais. Non réentrant : seuls les points
 * d'entrée publics le prennent, jamais les fonctions privées qu'ils appellent.
 */
internal class DeviceLocks {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(deviceId: Long, block: suspend () -> T): T =
        locks.computeIfAbsent(deviceId) { Mutex() }.withLock { block() }
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
    private val locks = DeviceLocks()

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

    /** Oublie un événement en pause : mémo local seulement, rien à retirer de l'appareil. */
    suspend fun deletePaused(paused: PausedCoverEvent) = locks.withLock(paused.deviceId) { pausedDao.delete(paused) }

    /**
     * Relit les événements du volet. Chaque lecture (affichage, validation avant création,
     * resynchronisation) purge au passage les uniques échus, journalisé : ils ne s'exécuteront plus.
     */
    private suspend fun readEvents(ip: String, device: Device, keepExpired: Boolean = false): RpcResult<List<CoverEvent>> {
        val jobs = when (val r = scheduleRpc.list(ip)) {
            is RpcResult.Success -> r.value.jobs
            is RpcResult.RpcError -> return r
            is RpcResult.Failure -> return r
        }
        val all = coverEventsFrom(jobs, device.switchId)
        val now = LocalDateTime.now()
        val (alive, expired) = splitForRead(all, now, keepExpired)
        for (e in expired) {
            val id = e.jobId ?: continue
            val ok = scheduleRpc.delete(ip, id) is RpcResult.Success
            logger.info(DiagnosticLogger.RPC, "Volet ${device.id} : événement unique échu purgé (job $id, ${if (ok) "ok" else "échec"})")
        }
        return RpcResult.Success(alive.sortedWith(coverEventDisplayOrder))
    }

    /** Lecture du worker de notifications : garde les uniques échus et ne purge rien. */
    suspend fun getEventsForNotifications(device: Device): RpcResult<List<CoverEvent>> {
        if (isDemo(device)) return RpcResult.Success(emptyList())
        return deviceRepository.withIp(device) { ip -> readEvents(ip, device, keepExpired = true) }.second
    }

    suspend fun getEvents(device: Device): RpcResult<List<CoverEvent>> {
        if (isDemo(device)) return RpcResult.Success(listOf(CoverEvent(21, 0, action = CoverEventAction.Close)))
        // Sous verrou : la lecture normale purge les uniques échus.
        return locks.withLock(device.id) { deviceRepository.withIp(device) { ip -> readEvents(ip, device) }.second }
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
     * [CoverEventResult.Error], jamais un faux doublon. Ne prend pas le verrou (l'appelant le tient).
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

    suspend fun add(device: Device, event: CoverEvent): CoverEventResult = locks.withLock(device.id) { addUnlocked(device, event) }

    private suspend fun addUnlocked(device: Device, event: CoverEvent): CoverEventResult = run(device, "ajout d'événement") { ip, existing ->
        validateNewEvents(existing, listOf(event), LocalDateTime.now())?.let { return@run it }
        val c = create(ip, device, event)
        if (c is RpcResult.Success) CoverEventResult.Success else fail(device, "ajout d'événement", c)
    }

    /** Crée [first] puis [second] ; si [second] échoue, [first] est supprimé (plage tout ou rien). */
    suspend fun addWindow(device: Device, first: CoverEvent, second: CoverEvent): CoverEventResult = locks.withLock(device.id) {
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
    }

    /**
     * Crée le nouvel événement puis supprime l'ancien : un échec ne perd jamais l'existant. Si la
     * création a réussi, c'est un succès même quand la suppression échoue (comme `updatePlanning`).
     */
    suspend fun update(device: Device, old: CoverEvent, new: CoverEvent): CoverEventResult = locks.withLock(device.id) {
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
    }

    suspend fun delete(device: Device, event: CoverEvent): CoverEventResult = locks.withLock(device.id) { deleteUnlocked(device, event) }

    private suspend fun deleteUnlocked(device: Device, event: CoverEvent): CoverEventResult {
        if (isDemo(device)) return CoverEventResult.Success
        val id = event.jobId ?: return CoverEventResult.Error
        // Un seul appel : la bascule entre adresses est sans risque ici.
        val r = deviceRepository.withIp(device) { ip -> scheduleRpc.delete(ip, id) }.second
        return if (r is RpcResult.Success) CoverEventResult.Success else fail(device, "suppression d'événement", r)
    }

    /** Supprime le job de l'appareil, puis mémorise l'événement localement (seulement si succès). */
    suspend fun pause(device: Device, event: CoverEvent): CoverEventResult = locks.withLock(device.id) {
        val result = deleteUnlocked(device, event)
        if (result == CoverEventResult.Success && !isDemo(device)) {
            pausedDao.insert(event.toPaused(device.id, System.currentTimeMillis()))
        }
        result
    }

    /** Recrée l'événement en pause ; la ligne locale n'est effacée qu'en cas de succès. */
    suspend fun resume(device: Device, paused: PausedCoverEvent): CoverEventResult = locks.withLock(device.id) {
        val event = runCatching { paused.toEvent() }.getOrElse {
            logger.warn(DiagnosticLogger.DB, "Volet ${device.id} : événement en pause illisible (ligne ${paused.id})")
            return@withLock CoverEventResult.Error
        }
        val result = addUnlocked(device, event)
        if (result == CoverEventResult.Success) pausedDao.delete(paused)
        result
    }

    /**
     * Met en conformité l'appel ntfy de chaque événement du volet selon les réglages actuels, en
     * place (`Schedule.Update`) : même job, même activation, appels tiers conservés. Idempotent :
     * un job déjà conforme n'est pas touché. Couvre aussi les jobs désactivés et les uniques échus.
     * @return faux au moindre échec (lecture ou mise à jour), pour que le rattrapage réessaie.
     */
    suspend fun resyncNtfy(device: Device): Boolean {
        if (isDemo(device)) return true
        return locks.withLock(device.id) { resyncNtfyUnlocked(device) }
    }

    private suspend fun resyncNtfyUnlocked(device: Device): Boolean {
        val (ip, listed) = deviceRepository.withIp(device) { ip -> scheduleRpc.list(ip) }
        val jobs = when (listed) {
            is RpcResult.Success -> listed.value.jobs
            else -> { fail(device, "resynchronisation ntfy (lecture)", listed); return false }
        }
        var ok = true
        for (job in jobs) {
            val event = coverEventsFrom(listOf(job), device.switchId).firstOrNull() ?: continue
            val calls = coverResyncCalls(job, ntfyTexts(device, event.action)?.let { coverNtfyCall(it) }) ?: continue
            val u = scheduleRpc.update(ip, job.id, calls)
            if (u is RpcResult.Success) {
                logger.info(DiagnosticLogger.RPC, "Volet ${device.id} : ntfy resynchronisé (job ${job.id})")
            } else {
                fail(device, "resynchronisation ntfy (mise à jour du job ${job.id})", u)
                ok = false
            }
        }
        return ok
    }
}
