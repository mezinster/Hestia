package kapoue.hestia.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.util.formatClockTime
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.dao.PresenceConfigDao
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PresenceConfig
import kapoue.hestia.data.notifications.PendingTimer
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.presence.ChargeScriptGenerator
import kapoue.hestia.data.presence.DeviceClock
import kapoue.hestia.data.presence.PresenceScriptGenerator
import kapoue.hestia.data.presence.PresenceState
import kapoue.hestia.data.presence.TimerNotifyScriptGenerator
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
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.FirmwareCheckResult
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.PresenceWindow
import kapoue.hestia.domain.model.isActiveNow
import kapoue.hestia.domain.model.isExpiredOnce
import kapoue.hestia.domain.model.onceEndAt
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Point d'accès unique aux appareils : persistance locale, interrogation réseau. */
@Singleton
class DeviceRepository @Inject constructor(
    private val deviceDao: DeviceDao,
    private val presenceConfigDao: PresenceConfigDao,
    private val rpcClient: ShellyRpcClient,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
    @ApplicationContext private val context: Context,
) {
    fun observeDevices(): Flow<List<Device>> = deviceDao.observeAll()

    fun observeDevice(id: Long): Flow<Device?> = deviceDao.observeById(id)

    suspend fun getDevicesOnce(): List<Device> = deviceDao.getAllOnce()

    suspend fun getDevice(id: Long): Device? = deviceDao.getById(id)

    /** Lit l'état courant d'un canal (allumé/éteint, minuteur, puissance). */
    suspend fun getStatus(device: Device): RpcResult<SwitchStatusResult> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return demoStatus(device)
        return rpcClient.getSwitchStatus(device.ipAddress, device.switchId)
    }

    /** Bascule d'un canal déclenchée par l'utilisateur. */
    suspend fun userToggle(device: Device, on: Boolean): RpcResult<SwitchSetResult> {
        // Appareils démo : succès sans réseau (l'état affiché reste piloté par demoStatus).
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(SwitchSetResult())
        // Extinction manuelle : retirer le script de notif de fin de minuteur AVANT de couper —
        // contrairement au script de coupure, il ne sait pas distinguer une fin naturelle d'une
        // extinction manuelle ; le supprimer avant l'extinction est ce qui l'empêche de se déclencher.
        if (!on) removeTimerNotifyScript(device)
        val result = rpcClient.setSwitch(device.ipAddress, device.switchId, on)
        if (result is RpcResult.Success) {
            // Extinction manuelle : un éventuel minuteur en attente est interrompu → pas de notif de fin.
            if (!on) appPreferences.removePendingTimer(device.id)
        }
        return result
    }

    /**
     * Allume le canal en armant un minuteur **one-shot** sur l'appareil (`toggle_after`).
     * Autonome ensuite : l'appareil gère le compte à rebours, le téléphone peut être fermé.
     *
     * Un seul appel RPC, et surtout **aucune écriture dans la configuration de l'appareil** : le
     * minuteur ne vaut que pour cet allumage-ci. C'est le correctif du bug où un minuteur arrivé
     * à son terme laissait `auto_off` armé, si bien que tout allumage ultérieur — y compris via
     * le bouton physique — se coupait tout seul.
     */
    suspend fun startTimer(device: Device, seconds: Int, detail: String?): RpcResult<Unit> {
        val set = rpcClient.setSwitch(device.ipAddress, device.switchId, on = true, toggleAfterSec = seconds)
        if (set is RpcResult.RpcError) return set
        if (set is RpcResult.Failure) return set
        // Notification de fin best-effort : un souci ici ne doit jamais remettre en cause le
        // minuteur, déjà armé avec succès sur l'appareil.
        ntfyTopic()?.let { topic ->
            deployTimerNotifyScript(device, topic, context.getString(R.string.notif_timer_ended, detail.orEmpty()))
        }
        rememberPendingTimer(device.id, seconds, detail, thresholdW = null)
        return RpcResult.Success(Unit)
    }

    /** Sujet ntfy si le service est activé, sinon null — condition unique à tester partout. */
    private fun ntfyTopic(): String? = if (appPreferences.ntfyEnabled.value) appPreferences.ntfyTopic.value else null

    /**
     * Déploie (ou réutilise) le script minimal qui notifie la fin naturelle d'un minuteur sans
     * coupure sur seuil — seul cas sans aucun script associé sinon. Best-effort, ne fait jamais
     * échouer l'appelant.
     */
    private suspend fun deployTimerNotifyScript(device: Device, topic: String, body: String) {
        val ip = device.ipAddress
        val scripts = rpcClient.scriptList(ip).getOrNull()?.scripts ?: return
        val scriptId = scripts.firstOrNull { it.name == TimerNotifyScriptGenerator.SCRIPT_NAME }?.id
            ?: rpcClient.scriptCreate(ip, TimerNotifyScriptGenerator.SCRIPT_NAME).getOrNull()?.id
            ?: return
        rpcClient.scriptStop(ip, scriptId)
        val code = TimerNotifyScriptGenerator.generate(device.switchId, scriptId, topic, device.name, body)
        rpcClient.scriptPutCode(ip, scriptId, code)
        rpcClient.scriptSetConfig(ip, scriptId, enable = true)
        rpcClient.scriptStart(ip, scriptId)
    }

    /** Supprime le script de notif de fin de minuteur s'il existe (best-effort). */
    private suspend fun removeTimerNotifyScript(device: Device) {
        val ip = device.ipAddress
        rpcClient.scriptList(ip).getOrNull()?.scripts?.firstOrNull { it.name == TimerNotifyScriptGenerator.SCRIPT_NAME }?.let {
            rpcClient.scriptStop(ip, it.id)
            rpcClient.scriptDelete(ip, it.id)
        }
    }

    /**
     * Comme [startTimer] (minuteur natif + compte à rebours), mais déploie en plus un script de
     * **coupure sur seuil de consommation** : la prise se coupe avant la fin si `apower` reste sous
     * [thresholdW] pendant 60 s. Réservé aux prises qui mesurent la puissance.
     */
    suspend fun startChargeTimer(device: Device, seconds: Int, thresholdW: Int, detail: String?): RpcResult<Unit> {
        val ip = device.ipAddress
        // 1. Minuteur natif (durée max + compte à rebours).
        rpcClient.setSwitch(ip, device.switchId, on = true, toggleAfterSec = seconds).errorOrNull()?.let { return it }

        // 2. Script de coupure conso, réutilisé ou créé.
        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val existing = list.getOrNull()?.scripts?.firstOrNull { it.name == ChargeScriptGenerator.SCRIPT_NAME }
        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, ChargeScriptGenerator.SCRIPT_NAME)
            create.errorOrNull()?.let { return it }
            create.getOrNull()!!.id
        }
        rpcClient.scriptStop(ip, scriptId)
        val topic = ntfyTopic()
        val code = ChargeScriptGenerator.generate(
            device.switchId, thresholdW, belowSec = 60, selfId = scriptId,
            ntfyTopic = topic, ntfyTitle = device.name,
            ntfyBody = if (topic != null) context.getString(R.string.notif_cutoff_triggered) else "",
        )
        rpcClient.scriptPutCode(ip, scriptId, code).errorOrNull()?.let { return it }
        rpcClient.scriptSetConfig(ip, scriptId, enable = true).errorOrNull()?.let { return it }
        rpcClient.scriptStart(ip, scriptId).errorOrNull()?.let { return it }

        rememberPendingTimer(device.id, seconds, detail, thresholdW = thresholdW)
        return RpcResult.Success(Unit)
    }

    /** Mémorise un minuteur en attente pour la notification de fin (voir [PendingTimer]). */
    private fun rememberPendingTimer(deviceId: Long, seconds: Int, detail: String?, thresholdW: Int?) {
        appPreferences.putPendingTimer(
            PendingTimer(deviceId, System.currentTimeMillis() + seconds * 1000L, detail.orEmpty(), thresholdW),
        )
    }

    /**
     * Vrai si le script de coupure sur seuil a **effectivement coupé** : il est encore présent mais
     * s'est auto-arrêté (`enable:false` + `Script.Stop`). Distingue une vraie coupure sur seuil
     * d'une extinction manuelle ou par le bouton physique (où le script tournerait encore).
     */
    suspend fun cutoffScriptFired(device: Device): Boolean {
        val entry = rpcClient.scriptList(device.ipAddress).getOrNull()?.scripts
            ?.firstOrNull { it.name == ChargeScriptGenerator.SCRIPT_NAME }
        return entry != null && !entry.running
    }

    /**
     * Annule le minuteur en **éteignant le canal** (ce qui annule le `toggle_after`), et supprime
     * un éventuel script de coupure conso resté actif.
     */
    suspend fun cancelTimer(device: Device): RpcResult<Unit> {
        val ip = device.ipAddress
        rpcClient.scriptList(ip).getOrNull()?.scripts?.firstOrNull { it.name == ChargeScriptGenerator.SCRIPT_NAME }?.let {
            rpcClient.scriptStop(ip, it.id)
            rpcClient.scriptDelete(ip, it.id)
        }
        removeTimerNotifyScript(device)
        return when (val set = rpcClient.setSwitch(device.ipAddress, device.switchId, on = false)) {
            is RpcResult.Success -> {
                appPreferences.removePendingTimer(device.id)
                RpcResult.Success(Unit)
            }
            is RpcResult.RpcError -> set
            is RpcResult.Failure -> set
        }
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

    /** Lit les plages de présence réellement embarquées dans le script (jamais supposées). */
    suspend fun getPresenceWindows(device: Device): RpcResult<List<PresenceWindow>> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(emptyList())
        val ip = device.ipAddress
        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val entry = list.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME }
            ?: return RpcResult.Success(emptyList())
        return when (val code = rpcClient.scriptGetCode(ip, entry.id)) {
            is RpcResult.Success -> RpcResult.Success(PresenceScriptGenerator.parse(code.value.data).orEmpty())
            is RpcResult.RpcError -> code
            is RpcResult.Failure -> code
        }
    }

    /**
     * Déploie **l'ensemble des plages** de présence dans un unique script `hestia_presence`.
     * Liste vide → arrête et supprime le script. Neutralise d'abord un `auto_off` posé hors
     * d'Hestia. La configuration voyage dans le script (relisible), Hestia ne stocke rien.
     */
    suspend fun setPresenceWindows(device: Device, windows: List<PresenceWindow>): RpcResult<Unit> {
        if (windows.isEmpty()) return stopPresence(device)
        val ip = device.ipAddress
        rpcClient.clearAutoOff(ip, device.switchId).errorOrNull()?.let { return it }

        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val existing = list.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME }
        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, PresenceScriptGenerator.SCRIPT_NAME)
            create.errorOrNull()?.let { return it }
            create.getOrNull()!!.id
        }

        rpcClient.scriptStop(ip, scriptId)
        val topic = ntfyTopic()
        // Plusieurs plages possibles par appareil : le script ne sait pas, au moment où il bascule,
        // laquelle a déclenché — texte générique plutôt qu'un horaire qui serait celui de la
        // mauvaise plage (contrairement au planning, qui n'a qu'un seul créneau).
        val code = PresenceScriptGenerator.generate(
            windows, device.switchId,
            ntfyTopic = topic, ntfyTitle = device.name,
            ntfyStartBody = if (topic != null) context.getString(R.string.notif_ntfy_presence_started) else "",
            ntfyEndBody = if (topic != null) context.getString(R.string.notif_ntfy_presence_ended) else "",
        )
        rpcClient.scriptPutCode(ip, scriptId, code).errorOrNull()?.let { return it }
        rpcClient.scriptSetConfig(ip, scriptId, enable = true).errorOrNull()?.let { return it }
        rpcClient.scriptStart(ip, scriptId).errorOrNull()?.let { return it }
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
        return RpcResult.Success(Unit)
    }

    /** Vrai si des intervalles hebdomadaires chevauchent une plage de présence de l'appareil. */
    private suspend fun overlapsPresence(device: Device, intervals: List<Pair<Int, Int>>): Boolean {
        val windows = getPresenceWindows(device).getOrNull().orEmpty()
        return windows.any { w ->
            ScheduleCodec.intervalsOverlap(intervals, ScheduleCodec.weeklyIntervals(w.startMinutes, w.endMinutes, ScheduleCodec.ALL_DAYS))
        }
    }

    /** Vrai si une plage de présence chevauche un planning existant (pour bloquer son ajout). */
    suspend fun presenceConflictsWithPlanning(device: Device, window: PresenceWindow): Boolean {
        val plannings = getPlannings(device).getOrNull().orEmpty()
        val wIv = ScheduleCodec.weeklyIntervals(window.startMinutes, window.endMinutes, ScheduleCodec.ALL_DAYS)
        return plannings.any { p ->
            ScheduleCodec.intervalsOverlap(wIv, ScheduleCodec.weeklyIntervals(p.startMinutes, p.endMinutes, p.conflictDays()))
        }
    }

    // --- Planning (composant Schedule natif de l'appareil) ---

    /**
     * Lit les plannings réellement présents sur l'appareil (jamais supposés). Un planning
     * **Unique** dont l'échéance est passée est supprimé de l'appareil dans la foulée (pas de
     * tâche de fond : le nettoyage se fait à l'occasion de la prochaine lecture).
     */
    suspend fun getPlannings(device: Device): RpcResult<List<Planning>> {
        // Appareils démo : aucun réseau (évite un timeout par tuile fictive à chaque relevé).
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(emptyList())
        return when (val r = rpcClient.scheduleList(device.ipAddress)) {
            is RpcResult.Success -> {
                val ip = device.ipAddress
                val all = reconstructPlannings(r.value.jobs, device.switchId)
                val (expired, active) = all.partition { it.isExpiredOnce() }
                if (expired.isNotEmpty()) {
                    logger.info(DiagnosticLogger.RPC, "Nettoyage de ${expired.size} planning(s) Unique expiré(s)")
                    expired.forEach { p ->
                        rpcClient.scheduleDelete(ip, p.onJobId)
                        rpcClient.scheduleDelete(ip, p.offJobId)
                        p.cutoffScriptId?.let { rpcClient.scriptStop(ip, it); rpcClient.scriptDelete(ip, it) }
                    }
                }
                // Seuil de coupure relu à part (un appel Script.GetCode par planning concerné) :
                // jamais stocké, toujours l'état réel du script sur l'appareil.
                val withThresholds = active.map { p ->
                    val scriptId = p.cutoffScriptId ?: return@map p
                    val code = rpcClient.scriptGetCode(ip, scriptId).getOrNull()?.data ?: return@map p
                    p.copy(cutoffThresholdW = ChargeScriptGenerator.parseThreshold(code))
                }
                RpcResult.Success(withThresholds)
            }
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }
    }

    /**
     * Reconstruit les plannings à partir des programmes cron. On ne retient que les jobs
     * « Switch.Set » du bon canal, puis on apparie chaque allumage à son extinction :
     * - **créneau récurrent de journée** : même jeu de jours, extinction plus tard dans la journée ;
     * - **récurrent de nuit** (passe minuit) : extinction le matin, sur les jours **décalés au
     *   lendemain** (voir la création) ;
     * - **Unique** : apparié par date (même date si extinction plus tard le même jour, sinon le
     *   lendemain pour un créneau de nuit), jamais par jour de semaine.
     * On prend à chaque fois l'extinction la plus proche. Les créneaux ne se chevauchant pas
     * (garanti à la création), l'appariement reste sans ambiguïté pour les plannings créés par Hestia.
     * Un éventuel second appel `Script.Start` (job allumage) donne [Planning.cutoffScriptId].
     */
    private fun reconstructPlannings(jobs: List<ScheduleJob>, switchId: Int): List<Planning> {
        data class Ev(val jobId: Int, val minutes: Int, val on: Boolean, val days: Set<Int>, val date: LocalDate?, val scriptId: Int?)
        val events = jobs.mapNotNull { job ->
            val call = job.calls.firstOrNull { it.method == "Switch.Set" } ?: return@mapNotNull null
            val params = call.params ?: return@mapNotNull null
            if (params["id"]?.jsonPrimitive?.intOrNull != switchId) return@mapNotNull null
            val on = params["on"]?.jsonPrimitive?.booleanOrNull ?: return@mapNotNull null
            val parsed = ScheduleCodec.parse(job.timespec) ?: return@mapNotNull null
            val scriptCall = job.calls.firstOrNull { it.method == "Script.Start" || it.method == "Script.Stop" }
            val scriptId = scriptCall?.params?.get("id")?.jsonPrimitive?.intOrNull
            Ev(job.id, parsed.hour * 60 + parsed.minute, on, parsed.days, parsed.date, scriptId)
        }
        val ons = events.filter { it.on }.sortedBy { it.minutes }
        val offs = events.filterNot { it.on }.toMutableList()
        val plannings = mutableListOf<Planning>()
        for (on in ons) {
            val match = if (on.date != null) {
                offs.filter { it.date == on.date && it.minutes > on.minutes }.minByOrNull { it.minutes }
                    ?: offs.filter { it.date == on.date.plusDays(1) }.minByOrNull { it.minutes }
            } else {
                offs.filter { it.days == on.days && it.minutes > on.minutes }.minByOrNull { it.minutes }
                    ?: offs.filter { it.days == ScheduleCodec.nextDay(on.days) }.minByOrNull { it.minutes }
            } ?: continue
            offs.remove(match)
            plannings += Planning(
                startHour = on.minutes / 60, startMinute = on.minutes % 60,
                endHour = match.minutes / 60, endMinute = match.minutes % 60,
                days = on.days, onJobId = on.jobId, offJobId = match.jobId, date = on.date,
                cutoffScriptId = on.scriptId,
            )
        }
        return plannings.sortedWith(compareBy({ it.startMinutes }, { it.endMinutes }))
    }

    /**
     * Crée un planning après contrôle de conflit (chevauchement d'un autre planning, présence
     * active, ou limite atteinte). Un planning = deux programmes cron : allumage puis extinction.
     * [date] non nul = planning **Unique** (une seule occurrence, à cette date précise) ; [days]
     * est alors ignoré. Le contrôle de chevauchement d'un Unique se fait sur le jour de semaine de
     * [date] : deux Uniques au même jour de semaine mais à des dates différentes peuvent donc se
     * signaler comme en conflit à tort (cas rare, accepté pour ne pas complexifier le contrôle).
     *
     * [cutoffThresholdW] : coupure sur seuil de consommation, Unique comme récurrent. Déploie un
     * script dédié à ce planning (jamais partagé, jamais réutilisé par un autre minuteur ou
     * planning : deux plannings à coupure indépendants ne doivent pas se marcher dessus). Pour un
     * récurrent, le script se réarme proprement à chaque occurrence (`Script.Start` après un
     * `Script.Stop` réexécute le script depuis le début, aucun état résiduel — validé en direct).
     */
    suspend fun createPlanning(
        device: Device,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        days: Set<Int>,
        date: LocalDate? = null,
        cutoffThresholdW: Int? = null,
    ): CreatePlanningResult {
        // Un Unique déjà révolu serait créé sur l'appareil puis supprimé quelques secondes après
        // par le nettoyage automatique (getPlannings) — sans retour à l'utilisateur. On le refuse net.
        if (date != null && !onceEndAt(startHour, startMinute, endHour, endMinute, date).isAfter(LocalDateTime.now())) {
            return CreatePlanningResult.PastOnce
        }
        val existing = when (val r = getPlannings(device)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return CreatePlanningResult.Error
            is RpcResult.Failure -> return CreatePlanningResult.Error
        }
        if (existing.size >= MAX_PLANNINGS) return CreatePlanningResult.LimitReached

        val startMin = startHour * 60 + startMinute
        val endMin = endHour * 60 + endMinute
        val conflictDays = date?.let { setOf(cronDayOf(it)) } ?: days
        val newIntervals = ScheduleCodec.weeklyIntervals(startMin, endMin, conflictDays)
        existing.firstOrNull { p ->
            ScheduleCodec.intervalsOverlap(newIntervals, ScheduleCodec.weeklyIntervals(p.startMinutes, p.endMinutes, p.conflictDays()))
        }?.let { return CreatePlanningResult.Conflict(it) }

        // Coexistence présence/planning autorisée, mais pas de chevauchement horaire (le planning,
        // horaire fixe, ne doit pas être contredit par une plage de présence sur le même créneau).
        if (device.hasScripting && overlapsPresence(device, newIntervals)) {
            return CreatePlanningResult.PresenceOverlap
        }

        val ip = device.ipAddress
        val scriptId = if (cutoffThresholdW != null) {
            createCutoffScript(ip, device.switchId, cutoffThresholdW, device.name) ?: return CreatePlanningResult.Error
        } else {
            null
        }

        // Créneau de nuit (fin plus tôt que le début) : l'extinction tombe le lendemain.
        val onTimespec = date?.let { ScheduleCodec.timespecOnce(startHour, startMinute, it) }
            ?: ScheduleCodec.timespec(startHour, startMinute, days)
        val offTimespec = if (date != null) {
            val offDate = if (endMin < startMin) date.plusDays(1) else date
            ScheduleCodec.timespecOnce(endHour, endMinute, offDate)
        } else {
            val offDays = if (endMin < startMin) ScheduleCodec.nextDay(days) else days
            ScheduleCodec.timespec(endHour, endMinute, offDays)
        }
        val ntfy = ntfyPlanningTexts(device, startHour, startMinute, endHour, endMinute)
        val onId = rpcClient.scheduleCreate(
            ip, onTimespec, device.switchId, on = true, scriptCallMethod = "Script.Start", scriptId = scriptId,
            ntfyTopic = ntfy?.topic, ntfyTitle = ntfy?.title, ntfyBody = ntfy?.startBody,
        ).getOrNull()?.id
        if (onId == null) {
            scriptId?.let { rpcClient.scriptDelete(ip, it) }
            return CreatePlanningResult.Error
        }
        val offId = rpcClient.scheduleCreate(
            ip, offTimespec, device.switchId, on = false, scriptCallMethod = "Script.Stop", scriptId = scriptId,
            ntfyTopic = ntfy?.topic, ntfyTitle = ntfy?.title, ntfyBody = ntfy?.endBody,
        ).getOrNull()?.id
        if (offId == null) {
            // Ne pas laisser un allumage orphelin si l'extinction échoue.
            rpcClient.scheduleDelete(ip, onId)
            scriptId?.let { rpcClient.scriptDelete(ip, it) }
            return CreatePlanningResult.Error
        }
        return CreatePlanningResult.Success
    }

    private data class NtfyPlanningTexts(val topic: String, val title: String, val startBody: String, val endBody: String)

    /** Textes ntfy d'un planning (début/fin), ou null si ntfy est désactivé — rien à générer. */
    private fun ntfyPlanningTexts(device: Device, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): NtfyPlanningTexts? {
        val topic = ntfyTopic() ?: return null
        val start = formatClockTime(startHour, startMinute)
        val end = formatClockTime(endHour, endMinute)
        return NtfyPlanningTexts(
            topic = topic,
            title = device.name,
            startBody = context.getString(R.string.notif_planning_started, start, end),
            endBody = context.getString(R.string.notif_planning_ended, start, end),
        )
    }

    /**
     * Crée un script de coupure sur seuil **dédié** (jamais partagé) avec le seuil donné. Nom
     * unique par appel : le script `hestia_charge` du minuteur peut rester sur l'appareil (il ne
     * se supprime jamais lui-même, seulement s'auto-désactive), un nom identique ferait échouer
     * `Script.Create`.
     */
    private suspend fun createCutoffScript(ip: String, switchId: Int, thresholdW: Int, ntfyTitle: String): Int? {
        val id = rpcClient.scriptCreate(ip, ChargeScriptGenerator.uniquePlanningScriptName()).getOrNull()?.id ?: return null
        val topic = ntfyTopic()
        val code = ChargeScriptGenerator.generate(
            switchId, thresholdW, belowSec = 60, selfId = id,
            ntfyTopic = topic, ntfyTitle = ntfyTitle,
            ntfyBody = if (topic != null) context.getString(R.string.notif_cutoff_triggered) else "",
        )
        rpcClient.scriptPutCode(ip, id, code).errorOrNull()?.let {
            rpcClient.scriptDelete(ip, id)
            return null
        }
        // Pas d'auto-démarrage au boot : c'est le planning (Script.Start dans le cron d'allumage)
        // qui l'active, jamais l'appareil de lui-même.
        rpcClient.scriptSetConfig(ip, id, enable = false).errorOrNull()?.let {
            rpcClient.scriptDelete(ip, id)
            return null
        }
        return id
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
        date: LocalDate? = null,
        cutoffThresholdW: Int? = null,
    ): CreatePlanningResult {
        if (date != null && !onceEndAt(startHour, startMinute, endHour, endMinute, date).isAfter(LocalDateTime.now())) {
            return CreatePlanningResult.PastOnce
        }
        val existing = when (val r = getPlannings(device)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return CreatePlanningResult.Error
            is RpcResult.Failure -> return CreatePlanningResult.Error
        }
        val startMin = startHour * 60 + startMinute
        val endMin = endHour * 60 + endMinute
        // Conflit avec les AUTRES plannings uniquement (on s'exclut soi-même).
        val conflictDays = date?.let { setOf(cronDayOf(it)) } ?: days
        val newIntervals = ScheduleCodec.weeklyIntervals(startMin, endMin, conflictDays)
        existing.filterNot { it.onJobId == old.onJobId && it.offJobId == old.offJobId }
            .firstOrNull { p ->
                ScheduleCodec.intervalsOverlap(newIntervals, ScheduleCodec.weeklyIntervals(p.startMinutes, p.endMinutes, p.conflictDays()))
            }
            ?.let { return CreatePlanningResult.Conflict(it) }
        if (device.hasScripting && overlapsPresence(device, newIntervals)) {
            return CreatePlanningResult.PresenceOverlap
        }

        val ip = device.ipAddress
        val scriptId = if (cutoffThresholdW != null) {
            createCutoffScript(ip, device.switchId, cutoffThresholdW, device.name) ?: return CreatePlanningResult.Error
        } else {
            null
        }

        val onTimespec = date?.let { ScheduleCodec.timespecOnce(startHour, startMinute, it) }
            ?: ScheduleCodec.timespec(startHour, startMinute, days)
        val offTimespec = if (date != null) {
            val offDate = if (endMin < startMin) date.plusDays(1) else date
            ScheduleCodec.timespecOnce(endHour, endMinute, offDate)
        } else {
            val offDays = if (endMin < startMin) ScheduleCodec.nextDay(days) else days
            ScheduleCodec.timespec(endHour, endMinute, offDays)
        }
        val ntfy = ntfyPlanningTexts(device, startHour, startMinute, endHour, endMinute)
        val onId = rpcClient.scheduleCreate(
            ip, onTimespec, device.switchId, on = true, scriptCallMethod = "Script.Start", scriptId = scriptId,
            ntfyTopic = ntfy?.topic, ntfyTitle = ntfy?.title, ntfyBody = ntfy?.startBody,
        ).getOrNull()?.id
        if (onId == null) {
            scriptId?.let { rpcClient.scriptDelete(ip, it) }
            return CreatePlanningResult.Error
        }
        val offId = rpcClient.scheduleCreate(
            ip, offTimespec, device.switchId, on = false, scriptCallMethod = "Script.Stop", scriptId = scriptId,
            ntfyTopic = ntfy?.topic, ntfyTitle = ntfy?.title, ntfyBody = ntfy?.endBody,
        ).getOrNull()?.id
        if (offId == null) {
            rpcClient.scheduleDelete(ip, onId)
            scriptId?.let { rpcClient.scriptDelete(ip, it) }
            return CreatePlanningResult.Error
        }
        // Nouveaux programmes en place : retirer les anciens (et l'ancien script de coupure, le cas échéant).
        rpcClient.scheduleDelete(ip, old.onJobId)
        rpcClient.scheduleDelete(ip, old.offJobId)
        old.cutoffScriptId?.let { rpcClient.scriptStop(ip, it); rpcClient.scriptDelete(ip, it) }
        return CreatePlanningResult.Success
    }

    /** Jour de semaine cron (0 = dimanche … 6 = samedi) d'une date. */
    private fun cronDayOf(date: LocalDate): Int = date.dayOfWeek.value % 7

    /** Jours utilisables pour un contrôle de chevauchement : jour de semaine de sa date pour un
     * planning Unique (dont [Planning.days] est vide), [Planning.days] tel quel sinon. */
    private fun Planning.conflictDays(): Set<Int> = date?.let { setOf(cronDayOf(it)) } ?: days

    /**
     * Supprime un planning = ses deux programmes cron (et son éventuel script de coupure dédié).
     * Si le planning est **en cours** (créneau actif), on **éteint** la prise dans la foulée :
     * supprimer l'allumeur sans éteindre laisserait la prise allumée sans extinction prévue.
     */
    suspend fun deletePlanning(device: Device, planning: Planning): RpcResult<Unit> {
        val ip = device.ipAddress
        rpcClient.scheduleDelete(ip, planning.onJobId).errorOrNull()?.let { return it }
        rpcClient.scheduleDelete(ip, planning.offJobId).errorOrNull()?.let { return it }
        planning.cutoffScriptId?.let { rpcClient.scriptStop(ip, it); rpcClient.scriptDelete(ip, it) }
        if (planning.isActiveNow()) {
            rpcClient.setSwitch(ip, device.switchId, on = false)
        }
        return RpcResult.Success(Unit)
    }

    /** Teste la connexion et déduit les capacités de l'appareil (rejette les Gen1). */
    suspend fun probe(ip: String): RpcResult<DeviceCapabilities> = rpcClient.probe(ip)

    // --- Mise à jour du firmware (vérification et installation, toujours à la demande) ---

    /**
     * Vérifie manuellement si une mise à jour du firmware est disponible. Combine la version
     * installée (`Shelly.GetDeviceInfo`) et les versions publiées (`Shelly.CheckForUpdate`).
     * Ne propose jamais l'installation d'une bêta (voir [FirmwareCheckResult.BetaOnly]).
     */
    /**
     * Version de firmware installée, lue **localement** (`Shelly.GetDeviceInfo` uniquement — pas
     * `Shelly.CheckForUpdate`, qui contacte les serveurs Shelly). Pour le journal de diagnostic :
     * null si l'appareil est injoignable, jamais une exception qui bloquerait le rapport.
     */
    suspend fun getInstalledFirmwareVersion(device: Device): String? {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return null
        return rpcClient.getDeviceInfo(device.ipAddress).getOrNull()?.ver
    }

    suspend fun checkFirmwareUpdate(device: Device): FirmwareCheckResult {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return FirmwareCheckResult.UpToDate("démo")
        val ip = device.ipAddress
        val installed = (rpcClient.getDeviceInfo(ip).getOrNull()?.ver) ?: return FirmwareCheckResult.Error
        val update = rpcClient.checkForUpdate(ip).getOrNull() ?: return FirmwareCheckResult.Error
        return when {
            update.stable != null -> FirmwareCheckResult.UpdateAvailable(installed, update.stable.version)
            update.beta != null -> FirmwareCheckResult.BetaOnly(installed, update.beta.version)
            else -> FirmwareCheckResult.UpToDate(installed)
        }
    }

    /**
     * Lance l'installation de la mise à jour stable. Asynchrone côté appareil : ce retour signale
     * seulement que l'installation a **démarré**, pas qu'elle a abouti — l'appareil redémarre pour
     * l'appliquer et devient temporairement injoignable.
     */
    suspend fun installFirmwareUpdate(device: Device): RpcResult<Unit> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(Unit)
        return when (val r = rpcClient.updateFirmware(device.ipAddress)) {
            is RpcResult.Success -> RpcResult.Success(Unit)
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }
    }

    /**
     * Redémarre l'appareil (dépannage, ex. script planté). Toujours à la demande explicite,
     * jamais bloqué par un minuteur en cours — c'est justement un des cas où ça peut servir.
     */
    suspend fun rebootDevice(device: Device): RpcResult<Unit> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(Unit)
        return when (val r = rpcClient.reboot(device.ipAddress)) {
            is RpcResult.Success -> RpcResult.Success(Unit)
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }
    }

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

    /**
     * Recrée les plannings et redéploie le script de présence de **tous les appareils
     * joignables** pour refléter l'état actuel de ntfy (activation, sujet) — appelé quand
     * l'utilisateur bascule le réglage. Best-effort par appareil : un appareil injoignable à cet
     * instant garde son ancien comportement jusqu'à sa prochaine modification (pas de tâche de
     * fond pour le rattraper tout seul).
     */
    suspend fun resyncNtfyForAllDevices() {
        for (device in getDevicesOnce()) resyncNtfyForDevice(device)
    }

    private suspend fun resyncNtfyForDevice(device: Device) {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return
        if (device.supportsSwitch) {
            for (p in getPlannings(device).getOrNull().orEmpty()) {
                updatePlanning(device, p, p.startHour, p.startMinute, p.endHour, p.endMinute, p.days, p.date, p.cutoffThresholdW)
            }
        }
        if (device.hasScripting) {
            val windows = getPresenceWindows(device).getOrNull()
            if (!windows.isNullOrEmpty()) setPresenceWindows(device, windows)
        }
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

    private companion object {
        const val DEMO_IP_PREFIX = "203.0.113." // RFC 5737 TEST-NET-3, jamais routable
        // Chaque planning = 2 programmes cron ; la prise en tient ~20, on plafonne à 10 plannings.
        const val MAX_PLANNINGS = 10
    }
}
