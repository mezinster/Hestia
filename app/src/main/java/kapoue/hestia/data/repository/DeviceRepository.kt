package kapoue.hestia.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.util.formatClockTime
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.dao.PausedPlanningDao
import kapoue.hestia.data.local.dao.PresenceConfigDao
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PausedPlanning
import kapoue.hestia.data.local.entity.PresenceConfig
import kapoue.hestia.data.notifications.PendingTimer
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.presence.ButtonTimerScriptGenerator
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
import kapoue.hestia.domain.model.LedNightModeState
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
    private val pausedPlanningDao: PausedPlanningDao,
    private val rpcClient: ShellyRpcClient,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
    @ApplicationContext private val context: Context,
) {
    // --- Bascule automatique entre les 1 ou 2 adresses IP d'un appareil ---

    /** Cache mémoire de l'emplacement (1 ou 2) qui a répondu en dernier, pour ne pas relire la base à chaque appel. */
    private val workingIpSlot = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    private fun currentSlot(device: Device): Int = workingIpSlot[device.id] ?: device.lastWorkingIpSlot ?: 1

    /** IP de l'emplacement actuellement jugé fonctionnel, sans aucun appel réseau. */
    private fun currentIp(device: Device): String =
        if (currentSlot(device) == 2 && device.ip2Address != null) device.ip2Address else device.ipAddress

    /**
     * IP réellement utilisée lors du dernier appel RPC réussi pour cet appareil (peut différer de
     * [Device.ipAddress] si la bascule a eu lieu sur le 2ᵉ emplacement) — pour affichage UI
     * seulement, aucun appel réseau. À lire après un appel comme [getStatus] pour être à jour.
     */
    fun activeIp(device: Device): String = currentIp(device)

    private suspend fun rememberWorkingSlot(device: Device, slot: Int) {
        if (workingIpSlot[device.id] == slot && device.lastWorkingIpSlot == slot) return
        workingIpSlot[device.id] = slot
        deviceDao.updateLastWorkingIpSlot(device.id, slot)
    }

    private fun RpcResult<*>.isConnectivityFailure(): Boolean =
        this is RpcResult.Failure && (kind == RpcFailure.TIMEOUT || kind == RpcFailure.UNREACHABLE)

    /**
     * Exécute [call] sur l'emplacement IP jugé fonctionnel ; en cas d'échec réseau (pas une erreur
     * applicative de l'appareil, qui elle prouve que l'adresse est la bonne) et si une deuxième
     * adresse existe, retente automatiquement dessus. Mémorise le changement d'emplacement pour
     * les appels suivants. Retourne l'IP effectivement utilisée, pour que l'appelant enchaîne
     * dessus le reste d'une séquence d'appels sans repasser par cette résolution à chaque fois.
     */
    private suspend fun <T> withIp(device: Device, call: suspend (ip: String) -> RpcResult<T>): Pair<String, RpcResult<T>> {
        val slots = if (currentSlot(device) == 2 && device.ip2Address != null) {
            listOf(2 to device.ip2Address, 1 to device.ipAddress)
        } else {
            listOfNotNull(1 to device.ipAddress, device.ip2Address?.let { 2 to it })
        }
        var last: Pair<String, RpcResult<T>>? = null
        for ((slot, ip) in slots) {
            val result = call(ip)
            last = ip to result
            if (!result.isConnectivityFailure()) {
                rememberWorkingSlot(device, slot)
                return last
            }
        }
        return last!!
    }

    fun observeDevices(): Flow<List<Device>> = deviceDao.observeAll()

    fun observeDevice(id: Long): Flow<Device?> = deviceDao.observeById(id)

    suspend fun getDevicesOnce(): List<Device> = deviceDao.getAllOnce()

    suspend fun getDevice(id: Long): Device? = deviceDao.getById(id)

    /** Lit l'état courant d'un canal (allumé/éteint, minuteur, puissance). */
    suspend fun getStatus(device: Device): RpcResult<SwitchStatusResult> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return demoStatus(device)
        return withIp(device) { ip -> rpcClient.getSwitchStatus(ip, device.switchId) }.second
    }

    /** Bascule d'un canal déclenchée par l'utilisateur. */
    suspend fun userToggle(device: Device, on: Boolean): RpcResult<SwitchSetResult> {
        // Appareils démo : succès sans réseau (l'état affiché reste piloté par demoStatus).
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(SwitchSetResult())
        // Extinction manuelle : retirer le script de notif de fin de minuteur AVANT de couper —
        // contrairement au script de coupure, il ne sait pas distinguer une fin naturelle d'une
        // extinction manuelle ; le supprimer avant l'extinction est ce qui l'empêche de se déclencher.
        if (!on) removeTimerNotifyScript(device)
        val (_, result) = withIp(device) { ip -> rpcClient.setSwitch(ip, device.switchId, on) }
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
        val (_, set) = withIp(device) { ip -> rpcClient.setSwitch(ip, device.switchId, on = true, toggleAfterSec = seconds) }
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
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        val scripts = listResult.getOrNull()?.scripts ?: return
        val scriptId = scripts.firstOrNull { it.name == TimerNotifyScriptGenerator.scriptName(device.switchId) }?.id
            ?: rpcClient.scriptCreate(ip, TimerNotifyScriptGenerator.scriptName(device.switchId)).getOrNull()?.id
            ?: return
        rpcClient.scriptStop(ip, scriptId)
        val code = TimerNotifyScriptGenerator.generate(device.switchId, scriptId, topic, device.name, body)
        rpcClient.scriptPutCode(ip, scriptId, code)
        rpcClient.scriptSetConfig(ip, scriptId, enable = true)
        rpcClient.scriptStart(ip, scriptId)
    }

    /** Supprime le script de notif de fin de minuteur s'il existe (best-effort). */
    private suspend fun removeTimerNotifyScript(device: Device) {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        listResult.getOrNull()?.scripts?.firstOrNull { it.name == TimerNotifyScriptGenerator.scriptName(device.switchId) }?.let {
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
        // 1. Minuteur natif (durée max + compte à rebours).
        val (ip, set) = withIp(device) { i -> rpcClient.setSwitch(i, device.switchId, on = true, toggleAfterSec = seconds) }
        set.errorOrNull()?.let { return it }

        // 2. Script de coupure conso, réutilisé ou créé.
        val topic = ntfyTopic()
        val deployed = deployChargeCutoffScript(
            ip, device.switchId, device.name, thresholdW, graceSec = 0,
            ntfyEndBody = if (topic != null) context.getString(R.string.notif_timer_ended, detail.orEmpty()) else "",
        )
        deployed.errorOrNull()?.let { return it }

        rememberPendingTimer(device.id, seconds, detail, thresholdW = thresholdW)
        return RpcResult.Success(Unit)
    }

    /**
     * Comme [startChargeTimer], mais **sans limite de durée** : aucun minuteur natif armé, la
     * prise reste allumée jusqu'à la coupure sur seuil. Période de grâce fixe de 15 minutes avant
     * toute surveillance de la consommation (voir [ChargeScriptGenerator]) — sans elle, un
     * appareil qui met un instant à vraiment tirer du courant risquerait une coupure immédiate,
     * plus gênant ici qu'avec une durée maximale en filet de sécurité comme dans [startChargeTimer].
     */
    suspend fun startUnlimitedChargeTimer(device: Device, thresholdW: Int): RpcResult<Unit> {
        val (ip, set) = withIp(device) { i -> rpcClient.setSwitch(i, device.switchId, on = true) }
        set.errorOrNull()?.let { return it }
        val deployed = deployChargeCutoffScript(
            ip, device.switchId, device.name, thresholdW, graceSec = UNLIMITED_CHARGE_GRACE_SEC, ntfyEndBody = "",
        )
        deployed.errorOrNull()?.let { return it }
        return RpcResult.Success(Unit)
    }

    /** Déploie (ou réutilise) le script de coupure sur seuil partagé, commun à [startChargeTimer]/[startUnlimitedChargeTimer]. */
    private suspend fun deployChargeCutoffScript(
        ip: String, switchId: Int, deviceName: String, thresholdW: Int, graceSec: Int, ntfyEndBody: String,
    ): RpcResult<Int> {
        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val existing = list.getOrNull()?.scripts?.firstOrNull { it.name == ChargeScriptGenerator.scriptName(switchId) }
        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, ChargeScriptGenerator.scriptName(switchId))
            create.errorOrNull()?.let { return it }
            create.getOrNull()!!.id
        }
        rpcClient.scriptStop(ip, scriptId)
        val topic = ntfyTopic()
        val code = ChargeScriptGenerator.generate(
            switchId, thresholdW, belowSec = 60, selfId = scriptId, graceSec = graceSec,
            ntfyTopic = topic, ntfyTitle = deviceName,
            ntfyBody = if (topic != null) context.getString(R.string.notif_cutoff_triggered) else "",
            ntfyEndBody = ntfyEndBody,
        )
        rpcClient.scriptPutCode(ip, scriptId, code).errorOrNull()?.let { return it }
        rpcClient.scriptSetConfig(ip, scriptId, enable = true).errorOrNull()?.let { return it }
        rpcClient.scriptStart(ip, scriptId).errorOrNull()?.let { return it }
        return RpcResult.Success(scriptId)
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
        val (_, listResult) = withIp(device) { ip -> rpcClient.scriptList(ip) }
        val entry = listResult.getOrNull()?.scripts
            ?.firstOrNull { it.name == ChargeScriptGenerator.scriptName(device.switchId) }
        return entry != null && !entry.running
    }

    /**
     * Annule le minuteur en **éteignant le canal** (ce qui annule le `toggle_after`), et supprime
     * un éventuel script de coupure conso resté actif.
     */
    suspend fun cancelTimer(device: Device): RpcResult<Unit> {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        listResult.getOrNull()?.scripts?.firstOrNull { it.name == ChargeScriptGenerator.scriptName(device.switchId) }?.let {
            rpcClient.scriptStop(ip, it.id)
            rpcClient.scriptDelete(ip, it.id)
        }
        removeTimerNotifyScript(device)
        return when (val set = rpcClient.setSwitch(ip, device.switchId, on = false)) {
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
    suspend fun getDeviceClock(device: Device): RpcResult<DeviceClock?> {
        val (_, r) = withIp(device) { ip -> rpcClient.getFullStatus(ip) }
        return when (r) {
            is RpcResult.Success -> {
                val sys = r.value.sys
                RpcResult.Success(sys?.unixtime?.let { DeviceClock(it, sys.time) })
            }
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }
    }

    /** État réel de la présence, lu via Script.List (jamais supposé). */
    suspend fun getPresenceState(device: Device): RpcResult<PresenceState> {
        val (_, r) = withIp(device) { ip -> rpcClient.scriptList(ip) }
        return when (r) {
            is RpcResult.Success -> {
                val entry = r.value.scripts.firstOrNull { it.name == PresenceScriptGenerator.scriptName(device.switchId) }
                RpcResult.Success(
                    PresenceState(deployed = entry != null, running = entry?.running == true, scriptId = entry?.id),
                )
            }
            is RpcResult.RpcError -> r
            is RpcResult.Failure -> r
        }
    }

    /** Lit les plages de présence réellement embarquées dans le script (jamais supposées). */
    suspend fun getPresenceWindows(device: Device): RpcResult<List<PresenceWindow>> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(emptyList())
        val (ip, list) = withIp(device) { i -> rpcClient.scriptList(i) }
        list.errorOrNull()?.let { return it }
        val entry = list.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.scriptName(device.switchId) }
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
        val (ip, clear) = withIp(device) { i -> rpcClient.clearAutoOff(i, device.switchId) }
        clear.errorOrNull()?.let { return it }

        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val existing = list.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.scriptName(device.switchId) }
        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, PresenceScriptGenerator.scriptName(device.switchId))
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
        val (ip, list) = withIp(device) { i -> rpcClient.scriptList(i) }
        list.errorOrNull()?.let { return it }
        val entry = list.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.scriptName(device.switchId) }
        if (entry != null) {
            rpcClient.scriptStop(ip, entry.id)
            rpcClient.scriptDelete(ip, entry.id).errorOrNull()?.let { return it }
        }
        return RpcResult.Success(Unit)
    }

    // --- Minuteur déclenché par le bouton physique ---
    // Un seul script partagé par appareil physique depuis le 2026-08-17 (voir
    // ButtonTimerScriptGenerator) — la limite Shelly de 3 scripts activés par appareil rendait
    // un script par canal intenable dès 3 canaux configurés sur un même bloc.

    /** [durationSeconds] null = sans limite de durée (seuil alors obligatoire, voir [ButtonTimerScriptGenerator]). */
    data class ButtonTimerConfig(val enabled: Boolean, val durationSeconds: Int?, val thresholdW: Int?)

    /** Tous les autres canaux du même appareil physique (même IP), [device] exclu. */
    private suspend fun siblingsSharingIp(device: Device): List<Device> =
        deviceDao.getAllOnce().filter { it.ipAddress == device.ipAddress && it.id != device.id }

    /**
     * Lit la config réellement déployée sur l'appareil (jamais supposée, jamais stockée par
     * Hestia) — tous canaux confondus. Migre au passage d'éventuels scripts hérités (un par
     * canal, avant le 2026-08-17) vers le script partagé, de façon transparente et automatique.
     */
    private suspend fun loadOrMigrateButtonTimerScript(device: Device): Pair<String, List<ButtonTimerScriptGenerator.ChannelConfig>> {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        val scripts = listResult.getOrNull()?.scripts.orEmpty()

        val shared = scripts.firstOrNull { it.name == ButtonTimerScriptGenerator.SCRIPT_NAME }
        if (shared != null) {
            val code = rpcClient.scriptGetCode(ip, shared.id).getOrNull()?.data ?: return ip to emptyList()
            val allDevices = listOf(device) + siblingsSharingIp(device)
            val configs = ButtonTimerScriptGenerator.parse(code).orEmpty().mapNotNull { (switchId, dur, thr) ->
                val name = allDevices.firstOrNull { it.switchId == switchId }?.name ?: return@mapNotNull null
                ButtonTimerScriptGenerator.ChannelConfig(switchId, dur, thr, name)
            }
            return ip to configs
        }

        // Pas de script partagé : reprendre d'éventuels scripts hérités (ancien format, un par
        // canal), les fusionner dans le nouveau, puis nettoyer les anciens — best-effort, jamais
        // bloquant si un des scripts hérités est illisible (juste ignoré).
        val allDevices = listOf(device) + siblingsSharingIp(device)
        val legacy = allDevices.mapNotNull { d ->
            val entry = scripts.firstOrNull { it.name == "hestia_button_timer_${d.switchId}" } ?: return@mapNotNull null
            val code = rpcClient.scriptGetCode(ip, entry.id).getOrNull()?.data ?: return@mapNotNull null
            val (dur, thr) = parseLegacyButtonTimerMarker(code) ?: return@mapNotNull null
            entry to ButtonTimerScriptGenerator.ChannelConfig(d.switchId, dur, thr, d.name)
        }
        if (legacy.isEmpty()) return ip to emptyList()
        val migrated = legacy.map { it.second }
        deployButtonTimerScript(ip, migrated)
        legacy.forEach { (entry, _) ->
            rpcClient.scriptStop(ip, entry.id)
            rpcClient.scriptDelete(ip, entry.id)
        }
        return ip to migrated
    }

    /** Ancien format (avant le 2026-08-17) : un marqueur `[durée,seuil]` par script, un script par canal. */
    private fun parseLegacyButtonTimerMarker(code: String): Pair<Int?, Int?>? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith("// hestia_button_timer:") } ?: return null
        val payload = line.trim().removePrefix("// hestia_button_timer:").trim()
        val rows = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<Int?>>(payload) }.getOrNull() ?: return null
        if (rows.size < 2) return null
        return rows[0] to rows[1]
    }

    /** Réécrit le script partagé avec exactement [configs] ; le supprime si la liste est vide. */
    private suspend fun deployButtonTimerScript(ip: String, configs: List<ButtonTimerScriptGenerator.ChannelConfig>): RpcResult<Unit> {
        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val existing = list.getOrNull()?.scripts?.firstOrNull { it.name == ButtonTimerScriptGenerator.SCRIPT_NAME }

        if (configs.isEmpty()) {
            existing?.let {
                rpcClient.scriptStop(ip, it.id)
                rpcClient.scriptDelete(ip, it.id).errorOrNull()?.let { e -> return e }
            }
            return RpcResult.Success(Unit)
        }

        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, ButtonTimerScriptGenerator.SCRIPT_NAME)
            create.errorOrNull()?.let { return it }
            create.getOrNull()!!.id
        }
        rpcClient.scriptStop(ip, scriptId)
        val topic = ntfyTopic()
        val code = ButtonTimerScriptGenerator.generate(
            configs,
            ntfyTopic = topic,
            ntfyEndBody = if (topic != null) context.getString(R.string.notif_button_timer_ended) else "",
            ntfyCutoffBody = if (topic != null) context.getString(R.string.notif_cutoff_triggered) else "",
        )
        rpcClient.scriptPutCode(ip, scriptId, code).errorOrNull()?.let { return it }
        // enable:true (contrairement au script de coupure d'un planning) : doit redémarrer seul
        // après un redémarrage de l'appareil, la fonctionnalité ne doit pas se désactiver silencieusement.
        rpcClient.scriptSetConfig(ip, scriptId, enable = true).errorOrNull()?.let { return it }
        rpcClient.scriptStart(ip, scriptId).errorOrNull()?.let { return it }
        return RpcResult.Success(Unit)
    }

    suspend fun getButtonTimerConfig(device: Device): ButtonTimerConfig {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return ButtonTimerConfig(false, DEFAULT_BUTTON_TIMER_SEC, null)
        val (_, configs) = loadOrMigrateButtonTimerScript(device)
        val mine = configs.firstOrNull { it.switchId == device.switchId }
            ?: return ButtonTimerConfig(false, DEFAULT_BUTTON_TIMER_SEC, null)
        return ButtonTimerConfig(true, mine.durationSec, mine.thresholdW)
    }

    /**
     * Active/reconfigure ou désactive le minuteur déclenché par le bouton physique **de ce
     * canal**, sans toucher aux autres canaux configurés du même appareil (le script, lui, est
     * partagé — réécrit en entier à chaque changement, avec la config de tous les canaux
     * concernés). [durationSeconds] null = sans limite de durée ([thresholdW] alors obligatoire,
     * imposé côté appelant).
     */
    suspend fun setButtonTimer(device: Device, enabled: Boolean, durationSeconds: Int?, thresholdW: Int?): RpcResult<Unit> {
        val (ip, existing) = loadOrMigrateButtonTimerScript(device)
        val others = existing.filterNot { it.switchId == device.switchId }
        val updated = if (enabled) {
            others + ButtonTimerScriptGenerator.ChannelConfig(device.switchId, durationSeconds, thresholdW, device.name)
        } else {
            others
        }
        return deployButtonTimerScript(ip, updated)
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
        val (ip, r) = withIp(device) { i -> rpcClient.scheduleList(i) }
        return when (r) {
            is RpcResult.Success -> {
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

        // getPlannings ci-dessus vient de réussir : l'emplacement IP qui fonctionne est à jour.
        val ip = currentIp(device)
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

        // getPlannings ci-dessus vient de réussir : l'emplacement IP qui fonctionne est à jour.
        val ip = currentIp(device)
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
        val (ip, first) = withIp(device) { i -> rpcClient.scheduleDelete(i, planning.onJobId) }
        first.errorOrNull()?.let { return it }
        rpcClient.scheduleDelete(ip, planning.offJobId).errorOrNull()?.let { return it }
        planning.cutoffScriptId?.let { rpcClient.scriptStop(ip, it); rpcClient.scriptDelete(ip, it) }
        if (planning.isActiveNow()) {
            rpcClient.setSwitch(ip, device.switchId, on = false)
        }
        return RpcResult.Success(Unit)
    }

    // --- Pause d'un planning (retiré de l'appareil, mémorisé le temps de la pause) ---

    fun observePausedPlannings(deviceId: Long): Flow<List<PausedPlanning>> =
        pausedPlanningDao.observeForDevice(deviceId)

    /**
     * Met en pause : retire réellement le planning de l'appareil (mêmes appels que
     * [deletePlanning], y compris l'extinction s'il est en cours), puis mémorise sa config
     * localement pour pouvoir le recréer à l'identique. Le mémo n'est écrit **qu'après** le succès
     * du retrait sur l'appareil — en cas d'échec (appareil injoignable), rien n'est mémorisé et le
     * planning reste tel quel, comme pour une suppression ratée.
     */
    suspend fun pausePlanning(device: Device, planning: Planning): RpcResult<Unit> {
        val result = deletePlanning(device, planning)
        if (result is RpcResult.Success) {
            pausedPlanningDao.insert(
                PausedPlanning(
                    deviceId = device.id,
                    startHour = planning.startHour,
                    startMinute = planning.startMinute,
                    endHour = planning.endHour,
                    endMinute = planning.endMinute,
                    days = planning.days.sorted().joinToString(","),
                    date = planning.date?.toString(),
                    cutoffThresholdW = planning.cutoffThresholdW,
                ),
            )
        }
        return result
    }

    /**
     * Réactive : recrée le planning sur l'appareil (mêmes contrôles de conflit qu'une création
     * normale — un autre planning ou une présence a pu apparaître entre-temps). Le mémo local
     * n'est effacé qu'en cas de succès ; sinon il reste en attente, réessayable.
     */
    suspend fun resumePlanning(device: Device, paused: PausedPlanning): CreatePlanningResult {
        val days = paused.days.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet()
        val date = paused.date?.let { LocalDate.parse(it) }
        val result = createPlanning(
            device, paused.startHour, paused.startMinute, paused.endHour, paused.endMinute,
            days, date, paused.cutoffThresholdW,
        )
        if (result is CreatePlanningResult.Success) {
            pausedPlanningDao.delete(paused)
        }
        return result
    }

    /** Oublie définitivement un planning en pause, sans le recréer sur l'appareil. */
    suspend fun deletePausedPlanning(paused: PausedPlanning) {
        pausedPlanningDao.delete(paused)
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
        val (_, r) = withIp(device) { ip -> rpcClient.getDeviceInfo(ip) }
        return r.getOrNull()?.ver
    }

    suspend fun checkFirmwareUpdate(device: Device): FirmwareCheckResult {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return FirmwareCheckResult.UpToDate("démo")
        val (ip, infoResult) = withIp(device) { i -> rpcClient.getDeviceInfo(i) }
        val installed = infoResult.getOrNull()?.ver ?: return FirmwareCheckResult.Error
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
        val (_, r) = withIp(device) { ip -> rpcClient.updateFirmware(ip) }
        return when (r) {
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
        val (_, r) = withIp(device) { ip -> rpcClient.reboot(ip) }
        return when (r) {
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
            val channelName = if (switchIds.size > 1) "$name · ${switchId + 1}" else name
            deviceDao.insert(
                Device(
                    name = channelName,
                    deviceName = name,
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

    /**
     * Met à jour les champs partagés d'un appareil physique (nom, adresses IP, type) sur **tous**
     * ses canaux d'un coup — jamais leurs noms de canal individuels ([Device.name], laissés tels
     * quels). Évite qu'une IP modifiée sur un seul canal d'un bloc multi-canaux laisse les autres
     * pointer sur l'ancienne (ils partagent tous la même prise physique).
     */
    suspend fun updateDeviceGroup(
        members: List<Device>,
        deviceName: String,
        ipAddress: String,
        ip2Address: String?,
        ipName: String?,
        ip2Name: String?,
        type: DeviceType,
    ) {
        for (device in members) {
            deviceDao.update(
                device.copy(
                    deviceName = deviceName,
                    ipAddress = ipAddress,
                    ip2Address = ip2Address,
                    ipName = ipName,
                    ip2Name = ip2Name,
                    type = type,
                ),
            )
        }
    }

    // --- LED d'état (nuit) ---

    /**
     * Détecte le composant LED réel de l'appareil physique et lit son état — jamais mémorisé
     * au-delà de l'appel, relu à chaque ouverture de l'écran comme tout le reste chez Hestia. Le
     * composant n'existe qu'une fois par appareil physique, jamais par canal ([members] sert
     * uniquement à choisir l'ordre de détection le plus probable — bloc multi-canaux vs prise
     * solo — et à résoudre l'IP courante). Nom **et casse** du composant variables selon le modèle
     * (`plugs_ui` en minuscules sur Plug M Gen3, `POWERSTRIP_UI` en majuscules sur Shelly Strip 4,
     * validé en direct le 2026-08-15) : essaie les combinaisons plausibles jusqu'à la première qui
     * répond, jamais supposée à l'avance.
     */
    suspend fun getLedState(members: List<Device>): Pair<String?, LedNightModeState> {
        val device = members.first()
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return null to LedNightModeState.UNAVAILABLE
        for (component in ledComponentCandidates(members.size > 1)) {
            val (_, result) = withIp(device) { ip -> rpcClient.ledUiGetConfig(ip, component) }
            when (result) {
                is RpcResult.Success -> {
                    val nightMode = result.value.leds?.nightMode
                    val state = if (nightMode?.enable == true && nightMode.brightness <= 0.0) {
                        LedNightModeState.OFF
                    } else {
                        LedNightModeState.ON
                    }
                    return component to state
                }
                // Injoignable : inutile d'essayer les autres noms, l'appareil ne répondra pas plus.
                is RpcResult.Failure -> return null to LedNightModeState.UNAVAILABLE
                // Mauvais nom/casse pour ce composant sur ce modèle : essai suivant.
                is RpcResult.RpcError -> continue
            }
        }
        return null to LedNightModeState.UNAVAILABLE
    }

    /**
     * Réécrit `night_mode` en entier. [enabled] = « allumée, réduite à [LED_NIGHT_BRIGHTNESS] %
     * de [LED_NIGHT_WINDOW] » ; sinon éteinte en permanence (0 %, fenêtre couvrant toute la
     * journée). [component] doit provenir d'un appel [getLedState] réussi pour ce même appareil.
     */
    suspend fun setLedState(members: List<Device>, component: String, enabled: Boolean): RpcResult<Unit> {
        val device = members.first()
        val brightness = if (enabled) LED_NIGHT_BRIGHTNESS else 0
        val window = if (enabled) LED_NIGHT_WINDOW else LED_ALWAYS_WINDOW
        val (_, result) = withIp(device) { ip -> rpcClient.ledUiSetConfig(ip, component, brightness, window) }
        return when (result) {
            is RpcResult.Success -> RpcResult.Success(Unit)
            is RpcResult.RpcError -> result
            is RpcResult.Failure -> result
        }
    }

    /** Ordre d'essai : nom probable en premier selon solo/bloc, casse basse puis haute. */
    private fun ledComponentCandidates(isGroup: Boolean): List<String> {
        val primary = if (isGroup) "powerstrip_ui" else "plugs_ui"
        val secondary = if (isGroup) "plugs_ui" else "powerstrip_ui"
        return listOf(primary, primary.uppercase(), secondary, secondary.uppercase())
    }

    suspend fun deleteDevice(device: Device) {
        deviceDao.delete(device)
        logger.info(DiagnosticLogger.DB, "Suppression appareil ${device.ipAddress} canal ${device.switchId}")
    }

    /** Supprime tous les canaux d'un même appareil physique d'un coup. */
    suspend fun deleteDeviceGroup(members: List<Device>) {
        for (device in members) deleteDevice(device)
    }

    /**
     * Réordonne pour l'affichage : les canaux d'un même appareil physique (même IP) restent
     * toujours groupés côte à côte, jamais mélangés avec un autre appareil entre eux — l'ordre
     * brut de `position` par canal individuel ne garantit pas cette contiguïté (ex. un canal
     * isolé d'un bloc de 4 prises pris en sandwich entre deux appareils sans rapport). Le groupe
     * prend la place du premier de ses canaux dans l'ordre existant ; à l'intérieur du groupe,
     * tri par canal (`switchId`). Ne modifie jamais `position` en base, uniquement l'affichage —
     * partagé entre le Tableau et Réglages pour un ordre toujours identique entre les deux écrans.
     */
    fun groupedForDisplay(devices: List<Device>): List<Device> {
        val firstIndexByIp = LinkedHashMap<String, Int>()
        devices.forEachIndexed { index, d -> firstIndexByIp.putIfAbsent(d.ipAddress, index) }
        return devices
            .groupBy { it.ipAddress }
            .entries
            .sortedBy { (ip, _) -> firstIndexByIp.getValue(ip) }
            .flatMap { (_, members) -> members.sortedBy { it.switchId } }
    }

    /**
     * Réordonnancement (Réglages, monter/descendre) : reçoit la liste complète déjà réordonnée
     * (groupes entiers déplacés en bloc, jamais un seul canal isolé) et réécrit `position` de
     * façon strictement séquentielle pour refléter ce nouvel ordre.
     */
    suspend fun reorderDevices(orderedDevices: List<Device>) {
        orderedDevices.forEachIndexed { index, device ->
            if (device.position != index) deviceDao.update(device.copy(position = index))
        }
    }

    /**
     * Corrige une fois pour toutes, au lancement (sans effet si déjà à jour, sûr à rappeler) :
     * 1. Noms de canaux ajoutés avant l'adoption du séparateur « · » (ex. « Shelly Strip 4 1 » →
     *    « Shelly Strip 4 · 1 »), uniquement les appareils multi-canaux.
     * 2. [Device.deviceName] non renseigné (appareils ajoutés avant ce champ) : pour un appareil
     *    seul, son propre nom. Pour un multi-canaux, le nom de base déduit d'un canal qui suit
     *    encore le format « Base · N » (peu importe lequel), ou à défaut le nom du 1ᵉʳ canal.
     */
    suspend fun fixLegacyChannelNames() {
        val byIp = getDevicesOnce().groupBy { it.ipAddress }
        for (members in byIp.values) {
            if (members.size <= 1) {
                val solo = members.first()
                if (solo.deviceName.isBlank()) deviceDao.update(solo.copy(deviceName = solo.name))
                continue
            }
            // Corrige d'abord le séparateur, en gardant trace des noms à jour localement (la
            // liste `members` ne reflète pas encore les écritures ci-dessous).
            val upToDateNames = HashMap<Long, String>()
            for (device in members) {
                val n = device.switchId + 1
                val correctSuffix = " · $n"
                val fixedName = if (!device.name.endsWith(correctSuffix) && device.name.endsWith(" $n")) {
                    device.name.removeSuffix(" $n") + correctSuffix
                } else {
                    device.name
                }
                upToDateNames[device.id] = fixedName
                if (fixedName != device.name) deviceDao.update(device.copy(name = fixedName))
            }
            if (members.any { it.deviceName.isBlank() }) {
                val patterned = members.sortedBy { it.switchId }
                    .firstOrNull { upToDateNames.getValue(it.id).endsWith(" · ${it.switchId + 1}") }
                val baseName = patterned?.let { upToDateNames.getValue(it.id).removeSuffix(" · ${it.switchId + 1}") }
                    ?: (members.minByOrNull { it.switchId } ?: members.first()).let { upToDateNames.getValue(it.id) }
                for (device in members) {
                    if (device.deviceName.isBlank()) deviceDao.update(device.copy(deviceName = baseName, name = upToDateNames.getValue(device.id)))
                }
            }
        }
    }

    /**
     * Recrée les plannings et redéploie le script de présence de **tous les appareils
     * joignables** pour refléter l'état actuel de ntfy (activation, sujet) — appelé quand
     * l'utilisateur bascule le réglage. Un appareil injoignable à cet instant n'est pas marqué à
     * jour : le Tableau le rattrapera de lui-même dès qu'il redeviendra joignable ([ntfyCatchUpIfNeeded]).
     */
    suspend fun resyncNtfyForAllDevices() {
        for (device in getDevicesOnce()) {
            if (resyncNtfyForDevice(device)) appPreferences.markNtfySynced(device.id)
        }
    }

    /**
     * Redéploie (planning, présence, minuteur bouton) avec le nom actuel de l'appareil — le nom
     * affiché dans les notifications ntfy est écrit en dur dans les scripts au moment de leur
     * déploiement, pas relu dynamiquement par la prise. Sans ce réappel après un renommage, les
     * notifications garderaient l'ancien nom indéfiniment. Best-effort : si l'appareil est
     * injoignable au moment du renommage, l'ancien nom reste dans les scripts jusqu'à la prochaine
     * modification qui les redéploie (limite connue, non résolue automatiquement pour l'instant).
     */
    suspend fun resyncDeviceName(device: Device) {
        resyncNtfyForDevice(device)
    }

    /**
     * Rattrapage best-effort : appelé par le Tableau à chaque relevé où l'appareil répond, ne
     * fait rien s'il est déjà à jour pour la génération ntfy courante (évite de tout recréer à
     * chaque cycle de 5 s). Aucune tâche de fond dédiée — le rattrapage n'a lieu que parce que le
     * Tableau interroge de toute façon déjà l'appareil.
     */
    suspend fun ntfyCatchUpIfNeeded(device: Device) {
        if (appPreferences.isNtfySynced(device.id)) return
        if (resyncNtfyForDevice(device)) appPreferences.markNtfySynced(device.id)
    }

    /** @return vrai si l'appareil était joignable (donc effectivement resynchronisé). */
    private suspend fun resyncNtfyForDevice(device: Device): Boolean {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return true
        var reachable = true
        if (device.supportsSwitch) {
            when (val r = getPlannings(device)) {
                is RpcResult.Success -> for (p in r.value) {
                    updatePlanning(device, p, p.startHour, p.startMinute, p.endHour, p.endMinute, p.days, p.date, p.cutoffThresholdW)
                }
                is RpcResult.RpcError, is RpcResult.Failure -> reachable = false
            }
        }
        if (device.hasScripting) {
            when (val r = getPresenceWindows(device)) {
                is RpcResult.Success -> if (r.value.isNotEmpty()) setPresenceWindows(device, r.value)
                is RpcResult.RpcError, is RpcResult.Failure -> reachable = false
            }
            if (reachable) {
                val buttonConfig = getButtonTimerConfig(device)
                if (buttonConfig.enabled) {
                    setButtonTimer(device, true, buttonConfig.durationSeconds, buttonConfig.thresholdW)
                }
            }
        }
        return reachable
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
        const val DEFAULT_BUTTON_TIMER_SEC = 1800
        /** Grâce avant surveillance en mode « sans limite de durée » (voir [ChargeScriptGenerator]). */
        const val UNLIMITED_CHARGE_GRACE_SEC = 15 * 60
        /** LED d'état : intensité et fenêtre nocturne quand allumée (voir [getLedState]). */
        const val LED_NIGHT_BRIGHTNESS = 30
        val LED_NIGHT_WINDOW = listOf("22:00", "08:00")
        /** LED éteinte en permanence : même mécanisme `night_mode`, fenêtre couvrant toute la journée. */
        val LED_ALWAYS_WINDOW = listOf("00:00", "23:59")
    }
}
