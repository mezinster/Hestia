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
    val others = existing.toMutableList().also { if (ignoring != null) it.remove(ignoring) }
    val duplicate = new.indices.any { i ->
        others.any { new[i].sameSlotAs(it) } || (i + 1 until new.size).any { j -> new[i].sameSlotAs(new[j]) }
    }
    if (duplicate) return CoverEventResult.Duplicate
    if (existing.size - (if (ignoring != null) 1 else 0) + new.size > MAX_COVER_EVENTS) return CoverEventResult.LimitReached
    return null
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

    /** Relit les événements du volet, purge au passage les uniques échus (journalisé). */
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

    /** Exécute [block] sur l'IP joignable ; [block] renvoie un résultat métier ou une erreur RPC. */
    private suspend fun run(device: Device, what: String, block: suspend (ip: String) -> RpcResult<CoverEventResult>): CoverEventResult {
        if (isDemo(device)) return CoverEventResult.Success
        return when (val r = deviceRepository.withIp(device, block).second) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> {
                logger.warn(DiagnosticLogger.RPC, "Volet ${device.id} : $what refusé par l'appareil (code ${r.code})")
                CoverEventResult.Error
            }
            is RpcResult.Failure -> {
                logger.warn(DiagnosticLogger.RPC, "Volet ${device.id} : $what impossible (appareil injoignable)")
                CoverEventResult.Error
            }
        }
    }

    /** Lit l'existant puis valide ; renvoie le refus éventuel (déjà enveloppé) ou null si accepté. */
    private suspend fun validate(
        ip: String,
        device: Device,
        new: List<CoverEvent>,
        ignoring: CoverEvent? = null,
    ): RpcResult<CoverEventResult?> = when (val existing = readEvents(ip, device)) {
        is RpcResult.Success -> RpcResult.Success(validateNewEvents(existing.value, new, LocalDateTime.now(), ignoring))
        is RpcResult.RpcError -> existing
        is RpcResult.Failure -> existing
    }

    /** Crée un événement (ntfy en dernier appel s'il est actif) et renvoie l'id du job créé. */
    private suspend fun create(ip: String, device: Device, event: CoverEvent): RpcResult<Int> =
        when (val r = scheduleRpc.create(ip, event, device.switchId, ntfyTexts(device, event.action))) {
            is RpcResult.Success -> RpcResult.Success(r.value.id)
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }

    suspend fun add(device: Device, event: CoverEvent): CoverEventResult = run(device, "ajout d'événement") { ip ->
        val refusal = when (val v = validate(ip, device, listOf(event))) {
            is RpcResult.Success -> v.value
            is RpcResult.RpcError -> return@run v
            is RpcResult.Failure -> return@run v
        }
        if (refusal != null) return@run RpcResult.Success(refusal)
        when (val c = create(ip, device, event)) {
            is RpcResult.Success -> RpcResult.Success(CoverEventResult.Success)
            is RpcResult.RpcError -> c
            is RpcResult.Failure -> c
        }
    }

    /** Crée [first] puis [second] ; si [second] échoue, [first] est supprimé (plage tout ou rien). */
    suspend fun addWindow(device: Device, first: CoverEvent, second: CoverEvent): CoverEventResult =
        run(device, "ajout de plage") { ip ->
            val refusal = when (val v = validate(ip, device, listOf(first, second))) {
                is RpcResult.Success -> v.value
                is RpcResult.RpcError -> return@run v
                is RpcResult.Failure -> return@run v
            }
            if (refusal != null) return@run RpcResult.Success(refusal)
            val firstId = when (val c = create(ip, device, first)) {
                is RpcResult.Success -> c.value
                is RpcResult.RpcError -> return@run c
                is RpcResult.Failure -> return@run c
            }
            when (val c = create(ip, device, second)) {
                is RpcResult.Success -> RpcResult.Success(CoverEventResult.Success)
                is RpcResult.RpcError -> { scheduleRpc.delete(ip, firstId); c }
                is RpcResult.Failure -> { scheduleRpc.delete(ip, firstId); c }
            }
        }

    /** Crée le nouvel événement puis supprime l'ancien : un échec ne perd jamais l'existant. */
    suspend fun update(device: Device, old: CoverEvent, new: CoverEvent): CoverEventResult =
        run(device, "modification d'événement") { ip ->
            val oldId = old.jobId ?: return@run RpcResult.Success(CoverEventResult.Error)
            val refusal = when (val v = validate(ip, device, listOf(new), ignoring = old)) {
                is RpcResult.Success -> v.value
                is RpcResult.RpcError -> return@run v
                is RpcResult.Failure -> return@run v
            }
            if (refusal != null) return@run RpcResult.Success(refusal)
            when (val c = create(ip, device, new)) {
                is RpcResult.Success -> when (val d = scheduleRpc.delete(ip, oldId)) {
                    is RpcResult.Success -> RpcResult.Success(CoverEventResult.Success)
                    is RpcResult.RpcError -> d
                    is RpcResult.Failure -> d
                }
                is RpcResult.RpcError -> c
                is RpcResult.Failure -> c
            }
        }

    suspend fun delete(device: Device, event: CoverEvent): CoverEventResult = run(device, "suppression d'événement") { ip ->
        val id = event.jobId ?: return@run RpcResult.Success(CoverEventResult.Error)
        when (val d = scheduleRpc.delete(ip, id)) {
            is RpcResult.Success -> RpcResult.Success(CoverEventResult.Success)
            is RpcResult.RpcError -> d
            is RpcResult.Failure -> d
        }
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
     * Réécrit chaque événement avec ou sans ntfy selon les réglages actuels : création du
     * remplaçant d'abord, suppression de l'ancien seulement après succès (jamais d'événement perdu).
     * @return faux si l'appareil est injoignable.
     */
    suspend fun resyncNtfy(device: Device): Boolean {
        if (isDemo(device)) return true
        var reachable = true
        val result = deviceRepository.withIp(device) { ip ->
            val events = when (val r = readEvents(ip, device)) {
                is RpcResult.Success -> r.value
                is RpcResult.RpcError -> return@withIp r
                is RpcResult.Failure -> return@withIp r
            }
            for (event in events) {
                val oldId = event.jobId ?: continue
                when (val c = create(ip, device, event)) {
                    is RpcResult.Success -> scheduleRpc.delete(ip, oldId)
                    is RpcResult.RpcError -> logger.warn(DiagnosticLogger.RPC, "Volet ${device.id} : resynchronisation ntfy refusée (code ${c.code})")
                    is RpcResult.Failure -> return@withIp c
                }
            }
            RpcResult.Success(Unit)
        }.second
        if (result is RpcResult.Failure) {
            logger.warn(DiagnosticLogger.RPC, "Volet ${device.id} : resynchronisation ntfy impossible (appareil injoignable)")
            reachable = false
        }
        return reachable
    }
}
