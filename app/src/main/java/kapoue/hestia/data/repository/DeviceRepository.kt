package kapoue.hestia.data.repository

import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.dao.ActivationLogDao
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.dao.PresenceConfigDao
import kapoue.hestia.data.local.entity.ActivationLog
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PresenceConfig
import kapoue.hestia.data.presence.DeviceClock
import kapoue.hestia.data.presence.PresenceScriptGenerator
import kapoue.hestia.data.presence.PresenceState
import kapoue.hestia.data.rpc.DeviceCapabilities
import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.ScheduleCodec
import kapoue.hestia.data.rpc.ShellyRpcClient
import kapoue.hestia.data.rpc.errorOrNull
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.data.rpc.model.ScheduleJob
import kapoue.hestia.data.rpc.model.SwitchSetResult
import kapoue.hestia.data.rpc.model.SwitchStatusResult
import kapoue.hestia.domain.model.ActivationAction
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.Planning
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Point d'accès unique aux appareils : persistance locale, interrogation réseau, journal. */
@Singleton
class DeviceRepository @Inject constructor(
    private val deviceDao: DeviceDao,
    private val activationLogDao: ActivationLogDao,
    private val presenceConfigDao: PresenceConfigDao,
    private val rpcClient: ShellyRpcClient,
    private val logger: DiagnosticLogger,
) {
    fun observeDevices(): Flow<List<Device>> = deviceDao.observeAll()

    fun observeDevice(id: Long): Flow<Device?> = deviceDao.observeById(id)

    fun observeRecentLogs(id: Long): Flow<List<ActivationLog>> = activationLogDao.observeRecent(id)

    suspend fun getDevicesOnce(): List<Device> = deviceDao.getAllOnce()

    suspend fun getDevice(id: Long): Device? = deviceDao.getById(id)

    /**
     * Lit l'état courant d'un canal (allumé/éteint, minuteur) et **journalise tout changement
     * d'état observé** non déclenché par l'application (fin de minuteur, interface web).
     */
    suspend fun getStatus(device: Device): RpcResult<SwitchStatusResult> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return demoStatus(device)
        val result = rpcClient.getSwitchStatus(device.ipAddress, device.switchId)
        if (result is RpcResult.Success) {
            val output = result.value.output
            // Comparer à l'état persisté en base (et non à l'objet reçu, qui peut être périmé).
            val previous = deviceDao.getLastKnownOutput(device.id)
            if (previous != output) {
                // Ne journaliser qu'à partir d'un état de référence connu (pas au tout 1er relevé).
                if (previous != null) {
                    logActivation(device.id, if (output) ActivationAction.TURNED_ON else ActivationAction.TURNED_OFF)
                }
                deviceDao.updateLastKnownOutput(device.id, output)
            }
        }
        return result
    }

    /** Bascule d'un canal déclenchée par l'utilisateur (journalisée). */
    suspend fun userToggle(device: Device, on: Boolean): RpcResult<SwitchSetResult> {
        // Appareils démo : succès sans réseau (l'état affiché reste piloté par demoStatus).
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(SwitchSetResult())
        val result = rpcClient.setSwitch(device.ipAddress, device.switchId, on)
        if (result is RpcResult.Success) {
            // Persister l'état commandé : évite de re-journaliser ce changement au prochain relevé.
            deviceDao.updateLastKnownOutput(device.id, on)
            logActivation(device.id, if (on) ActivationAction.TURNED_ON else ActivationAction.TURNED_OFF)
        }
        return result
    }

    /**
     * Allume le canal en armant un minuteur **one-shot** sur l'appareil (`toggle_after`).
     * Autonome ensuite : l'appareil gère le compte à rebours, le téléphone peut être fermé.
     * [detail] est journalisé (durée).
     *
     * Un seul appel RPC, et surtout **aucune écriture dans la configuration de l'appareil** : le
     * minuteur ne vaut que pour cet allumage-ci. C'est le correctif du bug où un minuteur arrivé
     * à son terme laissait `auto_off` armé, si bien que tout allumage ultérieur — y compris via
     * le bouton physique — se coupait tout seul.
     */
    suspend fun startTimer(device: Device, seconds: Int, detail: String?): RpcResult<Unit> =
        when (val set = rpcClient.setSwitch(device.ipAddress, device.switchId, on = true, toggleAfterSec = seconds)) {
            is RpcResult.Success -> {
                // Sortie commandée à ON (persistée) : la future extinction (fin de minuteur,
                // même appli fermée) sera détectée au relevé suivant et journalisée une fois.
                deviceDao.updateLastKnownOutput(device.id, true)
                logActivation(device.id, ActivationAction.TIMER_STARTED, detail)
                RpcResult.Success(Unit)
            }
            is RpcResult.RpcError -> set
            is RpcResult.Failure -> set
        }

    /**
     * Annule le minuteur en **éteignant le canal**. Annuler = ne plus laisser passer le courant.
     *
     * Un seul appel suffit : éteindre le canal annule d'office le `toggle_after` en cours, et il
     * n'y a plus aucune configuration à défaire côté appareil.
     */
    suspend fun cancelTimer(device: Device): RpcResult<Unit> =
        when (val set = rpcClient.setSwitch(device.ipAddress, device.switchId, on = false)) {
            is RpcResult.Success -> {
                deviceDao.updateLastKnownOutput(device.id, false)
                logActivation(device.id, ActivationAction.TIMER_CANCELLED)
                RpcResult.Success(Unit)
            }
            is RpcResult.RpcError -> set
            is RpcResult.Failure -> set
        }

    // --- Simulation de présence ---

    suspend fun getPresenceConfig(deviceId: Long): PresenceConfig? =
        presenceConfigDao.getForDevice(deviceId)

    /** Horloge de l'appareil pour le contrôle de dérive (epoch + heure rapportée). */
    suspend fun getDeviceClock(device: Device): RpcResult<DeviceClock?> =
        when (val r = rpcClient.getFullStatus(device.ipAddress)) {
            is RpcResult.Success -> {
                val sys = r.value.sys
                RpcResult.Success(sys?.unixtime?.let { DeviceClock(it, sys.time) })
            }
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }

    /** État réel de la présence, lu via Script.List (jamais supposé). */
    suspend fun getPresenceState(device: Device): RpcResult<PresenceState> =
        when (val r = rpcClient.scriptList(device.ipAddress)) {
            is RpcResult.Success -> {
                val entry = r.value.scripts.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME }
                RpcResult.Success(
                    PresenceState(deployed = entry != null, running = entry?.running == true, scriptId = entry?.id),
                )
            }
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }

    /**
     * Génère, pousse et démarre le script de présence. Décision A : neutralise d'abord un
     * `auto_off` qui aurait été posé **hors d'Hestia**, pour que le script soit seul maître du
     * relais. Réutilise un script `hestia_presence` existant, ne touche jamais un script d'un
     * autre nom.
     */
    suspend fun deployPresence(
        device: Device,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        marginMinutes: Int,
    ): RpcResult<Unit> {
        val ip = device.ipAddress
        // 1. Neutraliser un auto_off posé hors d'Hestia (interface web native, ancienne version) :
        //    il rentrerait en conflit avec les allumages pilotés par le script.
        rpcClient.clearAutoOff(ip, device.switchId).errorOrNull()?.let { return it }

        // 2. Réutiliser le script hestia_presence s'il existe, sinon le créer.
        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val existing = list.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME }
        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, PresenceScriptGenerator.SCRIPT_NAME)
            create.errorOrNull()?.let { return it }
            create.getOrNull()!!.id
        }

        // 3. Arrêter (au cas où) puis pousser le code, activer, démarrer.
        rpcClient.scriptStop(ip, scriptId)
        val code = PresenceScriptGenerator.generate(startHour, startMinute, endHour, endMinute, marginMinutes, device.switchId)
        rpcClient.scriptPutCode(ip, scriptId, code).errorOrNull()?.let { return it }
        rpcClient.scriptSetConfig(ip, scriptId, enable = true).errorOrNull()?.let { return it }
        rpcClient.scriptStart(ip, scriptId).errorOrNull()?.let { return it }

        // 4. Cache local + journal.
        val existingConfig = presenceConfigDao.getForDevice(device.id)
        presenceConfigDao.upsert(
            (existingConfig ?: PresenceConfig(deviceId = device.id, startHour = startHour, startMinute = startMinute, endHour = endHour, endMinute = endMinute))
                .copy(
                    startHour = startHour, startMinute = startMinute,
                    endHour = endHour, endMinute = endMinute,
                    randomMarginMinutes = marginMinutes,
                    shellyScriptId = scriptId, enabled = true,
                ),
        )
        logActivation(device.id, ActivationAction.PRESENCE_DEPLOYED)
        return RpcResult.Success(Unit)
    }

    /** Arrête et supprime le script de présence. Ne touche jamais un script d'un autre nom. */
    suspend fun stopPresence(device: Device): RpcResult<Unit> {
        val ip = device.ipAddress
        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val entry = list.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME }
        if (entry != null) {
            rpcClient.scriptStop(ip, entry.id)
            rpcClient.scriptDelete(ip, entry.id).errorOrNull()?.let { return it }
        }
        presenceConfigDao.getForDevice(device.id)?.let {
            presenceConfigDao.upsert(it.copy(enabled = false, shellyScriptId = null))
        }
        logActivation(device.id, ActivationAction.PRESENCE_STOPPED)
        return RpcResult.Success(Unit)
    }

    // --- Planning (composant Schedule natif de l'appareil) ---

    /** Lit les plannings réellement présents sur l'appareil (jamais supposés). */
    suspend fun getPlannings(device: Device): RpcResult<List<Planning>> =
        when (val r = rpcClient.scheduleList(device.ipAddress)) {
            is RpcResult.Success -> RpcResult.Success(reconstructPlannings(r.value.jobs, device.switchId))
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }

    /**
     * Reconstruit les plannings à partir des programmes cron. On ne retient que les jobs
     * « Switch.Set » du bon canal, puis on apparie allumage → extinction par jeu de jours
     * identique. Comme les créneaux ne se chevauchent pas (garanti à la création), le k-ᵉ
     * allumage d'un jeu de jours correspond au k-ᵉ extinction : l'appariement est sans ambiguïté.
     */
    private fun reconstructPlannings(jobs: List<ScheduleJob>, switchId: Int): List<Planning> {
        data class Ev(val jobId: Int, val minutes: Int, val on: Boolean, val days: Set<Int>)
        val events = jobs.mapNotNull { job ->
            val call = job.calls.singleOrNull() ?: return@mapNotNull null
            if (call.method != "Switch.Set") return@mapNotNull null
            val params = call.params ?: return@mapNotNull null
            if (params["id"]?.jsonPrimitive?.intOrNull != switchId) return@mapNotNull null
            val on = params["on"]?.jsonPrimitive?.booleanOrNull ?: return@mapNotNull null
            val parsed = ScheduleCodec.parse(job.timespec) ?: return@mapNotNull null
            Ev(job.id, parsed.hour * 60 + parsed.minute, on, parsed.days)
        }
        val plannings = mutableListOf<Planning>()
        events.groupBy { it.days }.forEach { (days, evs) ->
            val ons = evs.filter { it.on }.sortedBy { it.minutes }
            val offs = evs.filterNot { it.on }.sortedBy { it.minutes }
            for (i in 0 until minOf(ons.size, offs.size)) {
                plannings += Planning(
                    startHour = ons[i].minutes / 60, startMinute = ons[i].minutes % 60,
                    endHour = offs[i].minutes / 60, endMinute = offs[i].minutes % 60,
                    days = days, onJobId = ons[i].jobId, offJobId = offs[i].jobId,
                )
            }
        }
        return plannings.sortedWith(compareBy({ it.startMinutes }, { it.endMinutes }))
    }

    /**
     * Crée un planning après contrôle de conflit (chevauchement d'un autre planning, présence
     * active, ou limite atteinte). Un planning = deux programmes cron : allumage puis extinction.
     */
    suspend fun createPlanning(
        device: Device,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        days: Set<Int>,
    ): CreatePlanningResult {
        val existing = when (val r = getPlannings(device)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return CreatePlanningResult.Error
            is RpcResult.Failure -> return CreatePlanningResult.Error
        }
        if (existing.size >= MAX_PLANNINGS) return CreatePlanningResult.LimitReached

        val startMin = startHour * 60 + startMinute
        val endMin = endHour * 60 + endMinute
        existing.firstOrNull { p ->
            p.days.intersect(days).isNotEmpty() && startMin < p.endMinutes && p.startMinutes < endMin
        }?.let { return CreatePlanningResult.Conflict(it) }

        // Présence et planning pilotent tous deux le relais : exclusifs.
        if (device.hasScripting && getPresenceState(device).getOrNull()?.running == true) {
            return CreatePlanningResult.PresenceActive
        }

        val ip = device.ipAddress
        val onId = rpcClient.scheduleCreate(ip, ScheduleCodec.timespec(startHour, startMinute, days), device.switchId, on = true)
            .getOrNull()?.id ?: return CreatePlanningResult.Error
        val offId = rpcClient.scheduleCreate(ip, ScheduleCodec.timespec(endHour, endMinute, days), device.switchId, on = false)
            .getOrNull()?.id
        if (offId == null) {
            // Ne pas laisser un allumage orphelin si l'extinction échoue.
            rpcClient.scheduleDelete(ip, onId)
            return CreatePlanningResult.Error
        }
        logActivation(device.id, ActivationAction.PLANNING_ADDED, planningDetail(startHour, startMinute, endHour, endMinute))
        return CreatePlanningResult.Success
    }

    /**
     * Modifie un planning : contrôle de conflit (en s'excluant lui-même), puis remplace ses deux
     * programmes. On **crée d'abord** les nouveaux, on **supprime ensuite** les anciens : si le
     * réseau lâche en cours, on risque au pire un doublon (récupérable), jamais une perte.
     */
    suspend fun updatePlanning(
        device: Device,
        old: Planning,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        days: Set<Int>,
    ): CreatePlanningResult {
        val existing = when (val r = getPlannings(device)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return CreatePlanningResult.Error
            is RpcResult.Failure -> return CreatePlanningResult.Error
        }
        val startMin = startHour * 60 + startMinute
        val endMin = endHour * 60 + endMinute
        // Conflit avec les AUTRES plannings uniquement (on s'exclut soi-même).
        existing.filterNot { it.onJobId == old.onJobId && it.offJobId == old.offJobId }
            .firstOrNull { p -> p.days.intersect(days).isNotEmpty() && startMin < p.endMinutes && p.startMinutes < endMin }
            ?.let { return CreatePlanningResult.Conflict(it) }
        if (device.hasScripting && getPresenceState(device).getOrNull()?.running == true) {
            return CreatePlanningResult.PresenceActive
        }

        val ip = device.ipAddress
        val onId = rpcClient.scheduleCreate(ip, ScheduleCodec.timespec(startHour, startMinute, days), device.switchId, on = true)
            .getOrNull()?.id ?: return CreatePlanningResult.Error
        val offId = rpcClient.scheduleCreate(ip, ScheduleCodec.timespec(endHour, endMinute, days), device.switchId, on = false)
            .getOrNull()?.id
        if (offId == null) {
            rpcClient.scheduleDelete(ip, onId)
            return CreatePlanningResult.Error
        }
        // Nouveaux programmes en place : retirer les anciens.
        rpcClient.scheduleDelete(ip, old.onJobId)
        rpcClient.scheduleDelete(ip, old.offJobId)
        logActivation(device.id, ActivationAction.PLANNING_MODIFIED, planningDetail(startHour, startMinute, endHour, endMinute))
        return CreatePlanningResult.Success
    }

    /** Supprime un planning = ses deux programmes cron. */
    suspend fun deletePlanning(device: Device, planning: Planning): RpcResult<Unit> {
        val ip = device.ipAddress
        rpcClient.scheduleDelete(ip, planning.onJobId).errorOrNull()?.let { return it }
        rpcClient.scheduleDelete(ip, planning.offJobId).errorOrNull()?.let { return it }
        logActivation(
            device.id,
            ActivationAction.PLANNING_REMOVED,
            planningDetail(planning.startHour, planning.startMinute, planning.endHour, planning.endMinute),
        )
        return RpcResult.Success(Unit)
    }

    private fun planningDetail(sh: Int, sm: Int, eh: Int, em: Int): String =
        "%02d:%02d – %02d:%02d".format(sh, sm, eh, em)

    /** Teste la connexion et déduit les capacités de l'appareil (rejette les Gen1). */
    suspend fun probe(ip: String): RpcResult<DeviceCapabilities> = rpcClient.probe(ip)

    suspend fun channelExists(ip: String, switchId: Int): Boolean = deviceDao.exists(ip, switchId)

    /**
     * Ajoute un ou plusieurs canaux d'un même appareil physique, en **persistant les capacités
     * réellement détectées** par la sonde (source de vérité — le type reste cosmétique).
     * @return le nombre de canaux effectivement ajoutés.
     */
    suspend fun addChannels(
        name: String,
        ip: String,
        type: DeviceType,
        capabilities: DeviceCapabilities,
        switchIds: List<Int>,
    ): Int {
        var basePosition = deviceDao.maxPosition() + 1
        var added = 0
        for (switchId in switchIds) {
            if (deviceDao.exists(ip, switchId)) continue
            val channelName = if (switchIds.size > 1) "$name ${switchId + 1}" else name
            deviceDao.insert(
                Device(
                    name = channelName,
                    ipAddress = ip,
                    switchId = switchId,
                    type = type,
                    model = capabilities.model,
                    supportsSwitch = switchId in capabilities.switchChannels,
                    hasScripting = capabilities.hasScripting,
                    hasPowerMetering = capabilities.hasPowerMetering,
                    position = basePosition,
                ),
            )
            basePosition++
            added++
        }
        logger.info(DiagnosticLogger.DB, "Ajout appareil $ip : $added canal(aux) sur ${switchIds.size}")
        return added
    }

    suspend fun updateDevice(device: Device) = deviceDao.update(device)

    suspend fun deleteDevice(device: Device) {
        deviceDao.delete(device)
        logger.info(DiagnosticLogger.DB, "Suppression appareil ${device.ipAddress} canal ${device.switchId}")
    }

    /** Échange l'ordre d'affichage de deux appareils (réordonnancement). */
    suspend fun swapPositions(a: Device, b: Device) {
        deviceDao.update(a.copy(position = b.position))
        deviceDao.update(b.copy(position = a.position))
    }

    // --- Mode démo (captures d'écran, build debug uniquement) ---

    suspend fun hasDemoDevices(): Boolean = deviceDao.countDemoDevices() > 0

    suspend fun addDemoDevices() {
        if (deviceDao.countDemoDevices() > 0) return
        var position = deviceDao.maxPosition() + 1
        val demo = listOf(
            Triple("Prise scooter", "203.0.113.1", DeviceType.PLUG),
            Triple("Lampe salon", "203.0.113.2", DeviceType.LAMP),
            Triple("Radiateur", "203.0.113.3", DeviceType.PLUG),
            Triple("Prise bureau", "203.0.113.4", DeviceType.PLUG),
            Triple("Prise balcon", "203.0.113.5", DeviceType.PLUG),
        )
        for ((name, ip, type) in demo) {
            deviceDao.insert(
                Device(
                    name = name, ipAddress = ip, switchId = 0, type = type, model = "Démo",
                    supportsSwitch = true, hasScripting = true, hasPowerMetering = true, position = position,
                ),
            )
            position++
        }
    }

    suspend fun removeDemoDevices() = deviceDao.deleteDemoDevices()

    /** État injecté d'un appareil démo (varié pour de belles captures), sans aucun réseau. */
    private fun demoStatus(device: Device): RpcResult<SwitchStatusResult> {
        return when (device.ipAddress.substringAfterLast('.').toIntOrNull()) {
            2 -> RpcResult.Success(SwitchStatusResult(id = device.switchId, output = false)) // Repos
            3 -> RpcResult.Success(                                                          // Minuté
                SwitchStatusResult(
                    id = device.switchId, output = true,
                    timerStartedAt = System.currentTimeMillis() / 1000.0,
                    timerDuration = 5400.0,
                ),
            )
            4 -> RpcResult.Failure(RpcFailure.UNREACHABLE)                                   // Hors ligne
            else -> RpcResult.Success(                                                       // Actif
                SwitchStatusResult(id = device.switchId, output = true, apower = 479.3),
            )
        }
    }

    /**
     * Écrit une entrée de journal d'activité, sans jamais faire échouer l'action en cours,
     * et purge les entrées de plus de 30 jours.
     */
    private suspend fun logActivation(deviceId: Long, action: ActivationAction, detail: String? = null) {
        runCatching {
            activationLogDao.insert(ActivationLog(deviceId = deviceId, action = action, detail = detail))
            activationLogDao.purgeOlderThan(System.currentTimeMillis() - THIRTY_DAYS_MS)
        }
    }

    private companion object {
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
        const val DEMO_IP_PREFIX = "203.0.113." // RFC 5737 TEST-NET-3, jamais routable
        // Chaque planning = 2 programmes cron ; la prise en tient ~20, on plafonne à 10 plannings.
        const val MAX_PLANNINGS = 10
    }
}
