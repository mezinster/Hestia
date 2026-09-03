package kapoue.hestia.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.util.formatClockTime
import kapoue.hestia.data.cloud.ShellyCloudClient
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.dao.PausedPlanningDao
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PausedPlanning
import kapoue.hestia.data.notifications.PendingTimer
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.presence.ButtonTimerScriptGenerator
import kapoue.hestia.data.presence.ChargeScriptGenerator
import kapoue.hestia.data.presence.DeviceClock
import kapoue.hestia.data.presence.PresenceScriptGenerator
import kapoue.hestia.data.presence.SmokeRelayScriptGenerator
import kapoue.hestia.data.presence.TimerNotifyScriptGenerator
import kapoue.hestia.data.rpc.DeviceCapabilities
import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.ScheduleCodec
import kapoue.hestia.data.rpc.ShellyRpcClient
import kapoue.hestia.data.rpc.errorOrNull
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.data.rpc.model.ScheduleJob
import kapoue.hestia.data.rpc.model.ScriptEntry
import kapoue.hestia.data.rpc.model.SensorReadingResult
import kapoue.hestia.data.rpc.model.SwitchSetResult
import kapoue.hestia.data.rpc.model.SwitchStatusResult
import kapoue.hestia.di.ApplicationScope
import kapoue.hestia.domain.model.CloudInfo
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.FirmwareCheckResult
import kapoue.hestia.domain.model.LedNightModeState
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.PresenceWindow
import kapoue.hestia.domain.model.isActiveNow
import kapoue.hestia.domain.model.isExpiredOnce
import kapoue.hestia.domain.model.onceEndAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Résultat d'une lecture d'état, avec sa provenance — jamais silencieuse (voir CLAUDE.md) : le
 * Tableau affiche un petit picto nuage quand [viaCloud] est vrai, pour ne jamais laisser croire
 * qu'on est sur le réseau local alors que non.
 */
data class DeviceStatusResult(
    val result: RpcResult<SwitchStatusResult>,
    val viaCloud: Boolean,
)

/** Même principe que [DeviceStatusResult], pour un détecteur de fumée (voir SMOKE-DETECTOR.md). */
data class SensorStatusResult(
    val result: RpcResult<SensorReadingResult>,
    val viaCloud: Boolean,
)

/** Point d'accès unique aux appareils : persistance locale, interrogation réseau. */
@Singleton
class DeviceRepository @Inject constructor(
    private val deviceDao: DeviceDao,
    private val pausedPlanningDao: PausedPlanningDao,
    private val rpcClient: ShellyRpcClient,
    private val cloudClient: ShellyCloudClient,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
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

    /**
     * Lit l'état courant d'un canal (allumé/éteint, minuteur, puissance) — repli cloud automatique
     * si injoignable en local, la clé du compte est configurée dans Réglages, et le MAC de
     * l'appareil est en cache (voir [Device.cloudId], lot 2). Pour rafraîchir plusieurs appareils
     * d'un coup, préférer [getStatuses] : un seul appel cloud groupé au lieu d'un par appareil.
     */
    suspend fun getStatus(device: Device): DeviceStatusResult {
        val local = getLocalStatus(device)
        if (!local.isConnectivityFailure()) return DeviceStatusResult(local, viaCloud = false)
        val cloud = cloudStatusFallback(device)
        return if (cloud != null) DeviceStatusResult(cloud, viaCloud = true) else DeviceStatusResult(local, viaCloud = false)
    }

    /**
     * Comme [getStatus], mais pour plusieurs appareils à la fois : le local reste interrogé en
     * parallèle pour chacun comme avant, puis **un seul** appel cloud groupé (jusqu'à 10 appareils
     * physiques distincts par appel Shelly) couvre tous ceux qui ont échoué en local — jamais un
     * appel cloud par canal (un bloc à 4 canaux ne compte que pour 1 identifiant cloud), jamais de
     * sondage cloud pour un appareil déjà joignable en local.
     */
    suspend fun getStatuses(devices: List<Device>): Map<Long, DeviceStatusResult> = coroutineScope {
        val local = devices.map { device -> async { device to getLocalStatus(device) } }.awaitAll().toMap()
        val needFallback = local.filterValues { it.isConnectivityFailure() }.keys.filter { it.cloudDeviceId() != null }
        if (needFallback.isEmpty()) return@coroutineScope local.mapValues { DeviceStatusResult(it.value, viaCloud = false) }.mapKeys { it.key.id }

        val (authKey, server) = cloudCredentialsOrNull()
            ?: return@coroutineScope local.mapValues { DeviceStatusResult(it.value, viaCloud = false) }.mapKeys { it.key.id }
        val cloudIds = needFallback.mapNotNull { it.cloudDeviceId() }
        val cloudStatus = cloudClient.getBatchStatus(server, authKey, cloudIds)

        local.mapValues { (device, result) ->
            val cloudId = device.cloudDeviceId()
            if (result.isConnectivityFailure() && cloudId != null) {
                cloudStatus[cloudId]?.let { DeviceStatusResult(parseCloudSwitchStatus(it, device.switchId), viaCloud = true) }
                    ?: DeviceStatusResult(result, viaCloud = false)
            } else {
                DeviceStatusResult(result, viaCloud = false)
            }
        }.mapKeys { it.key.id }
    }

    /**
     * Lit l'état d'un détecteur de fumée (alarme, batterie, température) — même principe que
     * [getStatus]/[getStatuses] côté prises : RPC local en priorité, repli cloud groupé sinon
     * (voir SMOKE-DETECTOR.md). [device.hasPowerMetering]/[device.supportsSwitch] ne s'appliquent
     * pas à ce type, jamais appelés ici.
     */
    suspend fun getSensorStatus(device: Device): SensorStatusResult {
        val local = getLocalSensorStatus(device)
        if (!local.isConnectivityFailure()) return SensorStatusResult(local, viaCloud = false)
        val cloud = cloudSensorStatusFallback(device)
        return if (cloud != null) SensorStatusResult(cloud, viaCloud = true) else SensorStatusResult(local, viaCloud = false)
    }

    /** Comme [getStatuses], pour plusieurs détecteurs de fumée à la fois (un seul appel cloud groupé). */
    suspend fun getSensorStatuses(devices: List<Device>): Map<Long, SensorStatusResult> = coroutineScope {
        val local = devices.map { device -> async { device to getLocalSensorStatus(device) } }.awaitAll().toMap()
        val needFallback = local.filterValues { it.isConnectivityFailure() }.keys.filter { it.cloudDeviceId() != null }
        if (needFallback.isEmpty()) return@coroutineScope local.mapValues { SensorStatusResult(it.value, viaCloud = false) }.mapKeys { it.key.id }

        val (authKey, server) = cloudCredentialsOrNull()
            ?: return@coroutineScope local.mapValues { SensorStatusResult(it.value, viaCloud = false) }.mapKeys { it.key.id }
        val cloudIds = needFallback.mapNotNull { it.cloudDeviceId() }
        val cloudStatus = cloudClient.getBatchStatus(server, authKey, cloudIds)

        local.mapValues { (device, result) ->
            val cloudId = device.cloudDeviceId()
            if (result.isConnectivityFailure() && cloudId != null) {
                cloudStatus[cloudId]?.let { SensorStatusResult(parseCloudSensorStatus(it), viaCloud = true) }
                    ?: SensorStatusResult(result, viaCloud = false)
            } else {
                SensorStatusResult(result, viaCloud = false)
            }
        }.mapKeys { it.key.id }
    }

    /**
     * Coupe l'alarme sonore en cours (`Smoke.Mute`, voir SMOKE-DETECTOR.md) — action locale
     * uniquement, jamais de repli cloud (l'API Cloud Control ne propose pas d'appel de contrôle
     * pour ce composant, seulement de la lecture d'état).
     */
    suspend fun muteSmokeAlarm(device: Device): RpcResult<Unit> {
        val (_, result) = withIp(device) { ip -> rpcClient.muteSmoke(ip, device.switchId) }
        return when (result) {
            is RpcResult.Success -> RpcResult.Success(Unit)
            is RpcResult.RpcError -> result
            is RpcResult.Failure -> result
        }
    }

    private suspend fun getLocalSensorStatus(device: Device): RpcResult<SensorReadingResult> {
        val (ip, result) = withIp(device) { i -> rpcClient.getFullStatus(i) }
        // Même raison que getLocalStatus côté prises : remplir Device.cloudId dès qu'on en a
        // l'occasion, jamais garanti avant le moment précis où le repli cloud en a besoin.
        if (result is RpcResult.Success && device.cloudId == null) backfillCloudId(device, ip)
        return when (result) {
            is RpcResult.Success -> {
                val full = result.value
                RpcResult.Success(
                    SensorReadingResult(
                        alarm = full.smoke?.alarm ?: false,
                        mute = full.smoke?.mute ?: false,
                        batteryPercent = full.devicePower?.battery?.percent,
                        batteryError = !full.devicePower?.errors.isNullOrEmpty(),
                        temperatureC = full.temperature?.tC,
                        updatedAtEpochSec = System.currentTimeMillis() / 1000,
                    ),
                )
            }
            is RpcResult.RpcError -> result
            is RpcResult.Failure -> result
        }
    }

    private suspend fun cloudSensorStatusFallback(device: Device): RpcResult<SensorReadingResult>? {
        val cloudId = device.cloudDeviceId() ?: return null
        val (authKey, server) = cloudCredentialsOrNull() ?: return null
        val cloudStatus = cloudClient.getBatchStatus(server, authKey, listOf(cloudId))
        return cloudStatus[cloudId]?.let { parseCloudSensorStatus(it) }
    }

    /**
     * Reconstruit un [SensorReadingResult] à partir du bloc `status` du cloud — mêmes champs que
     * le RPC local. `_updated` (ex. `"2026-08-31 17:04:07"`) n'est pas documenté explicitement par
     * Shelly mais confirmé présent en pratique le 2026-08-31 (voir SMOKE-DETECTOR.md) ; supposé en
     * UTC faute de certitude — à corriger si un décalage est constaté à l'usage.
     */
    private fun parseCloudSensorStatus(deviceStatus: JsonObject): RpcResult<SensorReadingResult> {
        val smoke = deviceStatus["smoke:0"]?.jsonObject
        val devicePower = deviceStatus["devicepower:0"]?.jsonObject
        val battery = devicePower?.get("battery")?.jsonObject
        val errors = devicePower?.get("errors")?.jsonArray
        val temperature = deviceStatus["temperature:0"]?.jsonObject
        val updated = deviceStatus["_updated"]?.jsonPrimitive?.contentOrNull
        return RpcResult.Success(
            SensorReadingResult(
                alarm = smoke?.get("alarm")?.jsonPrimitive?.booleanOrNull ?: false,
                mute = smoke?.get("mute")?.jsonPrimitive?.booleanOrNull ?: false,
                batteryPercent = battery?.get("percent")?.jsonPrimitive?.intOrNull,
                batteryError = errors != null && errors.isNotEmpty(),
                temperatureC = temperature?.get("tC")?.jsonPrimitive?.doubleOrNull,
                updatedAtEpochSec = updated?.let { parseCloudUpdatedTimestamp(it) },
            ),
        )
    }

    /** Parse `"yyyy-MM-dd HH:mm:ss"` (format `_updated` du cloud), supposé UTC — voir [parseCloudSensorStatus]. */
    private fun parseCloudUpdatedTimestamp(text: String): Long? = runCatching {
        LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toEpochSecond(ZoneOffset.UTC)
    }.getOrNull()

    /**
     * Identifiant cloud tel qu'attendu par l'API Shelly : MAC en minuscules, sans séparateur.
     * [Device.cloudId] est mis en cache tel que lu (`Shelly.GetDeviceInfo.mac`, en MAJUSCULES —
     * pour rester identique au « Cloud ID » affiché par l'interface native de l'appareil, voir
     * l'écran Modifier), donc jamais utilisable tel quel pour appeler le cloud : bug vécu en
     * direct le 2026-08-20, un appel qui réussit (200 OK) mais ne trouve aucun appareil, faute de
     * casse correcte — normalisé ici, au point d'usage, plutôt que supposé correct en cache.
     */
    private fun Device.cloudDeviceId(): String? = cloudId?.lowercase()

    private suspend fun getLocalStatus(device: Device): RpcResult<SwitchStatusResult> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return demoStatus(device)
        val (ip, result) = withIp(device) { i -> rpcClient.getSwitchStatus(i, device.switchId) }
        // Sans ça, Device.cloudId ne se remplirait qu'en ouvrant Modifier sur un appareil
        // joignable — jamais garanti avant le moment précis où on en a besoin (repli cloud, lot 3,
        // bug vécu en direct le 2026-08-20 : clé configurée mais jamais utilisée faute de MAC en
        // cache). Best-effort, détaché de ce relevé : ne retarde ni ne fait jamais échouer l'affichage.
        if (result is RpcResult.Success && device.cloudId == null) backfillCloudId(device, ip)
        return result
    }

    private fun backfillCloudId(device: Device, ip: String) {
        appScope.launch {
            rpcClient.getDeviceInfo(ip).getOrNull()?.mac?.let { mac ->
                cacheCloudId(listOf(device) + siblingsSharingIp(device), mac)
            }
        }
    }

    private suspend fun cloudStatusFallback(device: Device): RpcResult<SwitchStatusResult>? {
        val cloudId = device.cloudDeviceId() ?: return null
        val (authKey, server) = cloudCredentialsOrNull() ?: return null
        val cloudStatus = cloudClient.getBatchStatus(server, authKey, listOf(cloudId))
        return cloudStatus[cloudId]?.let { parseCloudSwitchStatus(it, device.switchId) }
    }

    private fun cloudCredentialsOrNull(): Pair<String, String>? {
        val authKey = appPreferences.cloudAuthKey.value ?: return null
        val server = appPreferences.cloudServer.value ?: return null
        return authKey to server
    }

    /** Reconstruit un [SwitchStatusResult] à partir du bloc `status` du cloud — mêmes champs que le RPC local. */
    private fun parseCloudSwitchStatus(deviceStatus: JsonObject, switchId: Int): RpcResult<SwitchStatusResult> {
        val switchJson = deviceStatus["switch:$switchId"]?.jsonObject
            ?: return RpcResult.Failure(RpcFailure.UNREACHABLE)
        return RpcResult.Success(
            SwitchStatusResult(
                id = switchId,
                output = switchJson["output"]?.jsonPrimitive?.booleanOrNull ?: false,
                apower = switchJson["apower"]?.jsonPrimitive?.doubleOrNull,
                timerStartedAt = switchJson["timer_started_at"]?.jsonPrimitive?.doubleOrNull,
                timerDuration = switchJson["timer_duration"]?.jsonPrimitive?.doubleOrNull,
                source = switchJson["source"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }

    /**
     * Bascule d'un canal déclenchée par l'utilisateur — repli cloud automatique si injoignable en
     * local (mêmes conditions que [getStatus]).
     */
    suspend fun userToggle(device: Device, on: Boolean): RpcResult<SwitchSetResult> {
        // Appareils démo : succès sans réseau (l'état affiché reste piloté par demoStatus).
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(SwitchSetResult())
        // Extinction manuelle : retirer le script de notif de fin de minuteur AVANT de couper —
        // contrairement au script de coupure, il ne sait pas distinguer une fin naturelle d'une
        // extinction manuelle ; le supprimer avant l'extinction est ce qui l'empêche de se déclencher.
        if (!on) removeTimerNotifyScript(device)
        val (_, local) = withIp(device) { ip -> rpcClient.setSwitch(ip, device.switchId, on) }
        val result = if (local.isConnectivityFailure()) cloudToggleFallback(device, on) ?: local else local
        if (result is RpcResult.Success) {
            // Extinction manuelle : un éventuel minuteur en attente est interrompu → pas de notif de fin.
            if (!on) appPreferences.removePendingTimer(device.id)
        }
        return result
    }

    private suspend fun cloudToggleFallback(device: Device, on: Boolean): RpcResult<SwitchSetResult>? {
        val cloudId = device.cloudDeviceId() ?: return null
        val (authKey, server) = cloudCredentialsOrNull() ?: return null
        val ok = cloudClient.setSwitch(server, authKey, cloudId, device.switchId, on)
        logger.info(DiagnosticLogger.RPC, "Repli cloud ${device.ipAddress}#${device.switchId} → ${if (ok) "réussi" else "échoué"}")
        return if (ok) RpcResult.Success(SwitchSetResult()) else null
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
        // enable=false (2026-08-22) : script transitoire — le minuteur natif qu'il accompagne ne
        // survit pas à un redémarrage de l'appareil, lui non plus ne doit pas redémarrer au boot.
        // Avec enable=true, il repartait avec la prise et envoyait une notification « minuteur
        // terminé » périmée à la première extinction manuelle venue, même des jours plus tard.
        // Même choix que le script de coupure d'un planning (seul à faire déjà enable=false).
        rpcClient.scriptSetConfig(ip, scriptId, enable = false)
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
     * Comme [startTimer] (minuteur natif + compte à rebours), mais déploie en plus une entrée
     * dans le script superviseur de **coupure sur seuil de consommation** : la prise se coupe
     * avant la fin si `apower` reste sous [thresholdW] pendant 60 s. Réservé aux prises qui
     * mesurent la puissance.
     */
    suspend fun startChargeTimer(device: Device, seconds: Int, thresholdW: Int, detail: String?): RpcResult<Unit> {
        // 1. Minuteur natif (durée max + compte à rebours).
        val (ip, set) = withIp(device) { i -> rpcClient.setSwitch(i, device.switchId, on = true, toggleAfterSec = seconds) }
        set.errorOrNull()?.let { return it }

        // 2. Ce canal dans le script superviseur de coupure — les autres canaux qu'il surveille
        // éventuellement déjà ne sont jamais touchés (voir upsertChargeSupervisorChannel).
        cleanupLegacyChargeScripts(ip, device)
        val endBody = context.getString(R.string.notif_timer_ended, detail.orEmpty())
        val config = ChargeScriptGenerator.ChannelConfig(device.switchId, thresholdW, graceSec = 0, device.name, endBody)
        upsertChargeSupervisorChannel(ip, config).errorOrNull()?.let { return it }

        rememberPendingTimer(device.id, seconds, detail, thresholdW = thresholdW)
        return RpcResult.Success(Unit)
    }

    /**
     * Comme [startChargeTimer], mais **sans limite de durée** : aucun minuteur natif armé, la
     * prise reste allumée jusqu'à la coupure sur seuil. Période de grâce fixe de 15 minutes avant
     * toute surveillance de la consommation (voir [ChargeScriptGenerator]) — sans elle, un
     * appareil qui met un instant à vraiment tirer du courant risquerait une coupure immédiate,
     * plus gênant ici qu'avec une durée maximale en filet de sécurité comme dans [startChargeTimer].
     * Pas de fin naturelle à notifier ici (pas de minuteur natif), donc [ChargeScriptGenerator.
     * ChannelConfig.endBody] vide.
     */
    suspend fun startUnlimitedChargeTimer(device: Device, thresholdW: Int): RpcResult<Unit> {
        val (ip, set) = withIp(device) { i -> rpcClient.setSwitch(i, device.switchId, on = true) }
        set.errorOrNull()?.let { return it }

        cleanupLegacyChargeScripts(ip, device)
        val config = ChargeScriptGenerator.ChannelConfig(
            device.switchId, thresholdW, graceSec = UNLIMITED_CHARGE_GRACE_SEC, device.name, endBody = "",
        )
        return upsertChargeSupervisorChannel(ip, config)
    }

    /**
     * Supprime d'éventuels scripts hérités (un par canal, avant le 2026-08-17) : transitoires par
     * nature (se désactivent seuls après usage), donc aucun état en cours à migrer contrairement
     * au minuteur bouton — un simple nettoyage best-effort suffit.
     */
    private suspend fun cleanupLegacyChargeScripts(ip: String, device: Device) {
        val scripts = rpcClient.scriptList(ip).getOrNull()?.scripts.orEmpty()
        (listOf(device) + siblingsSharingIp(device)).forEach { d ->
            scripts.firstOrNull { it.name == "hestia_charge_${d.switchId}" }?.let {
                rpcClient.scriptStop(ip, it.id)
                rpcClient.scriptDelete(ip, it.id)
            }
        }
    }

    /**
     * Ajoute [config] au script superviseur partagé de coupure sur seuil, ou le remplace s'il
     * suivait déjà ce canal (nouveau minuteur relancé dessus). **Ne touche jamais** aux autres
     * canaux déjà surveillés : si le script tourne déjà, la mutation passe par `Script.Eval`
     * (mémoire du script inchangée pour tout le reste, voir [ChargeScriptGenerator]) — seul un
     * premier déploiement (aucun script, ou existant mais arrêté) réécrit le code en dur, ce qui
     * est sans risque puisqu'il n'y a alors rien d'autre en cours à préserver.
     */
    private suspend fun upsertChargeSupervisorChannel(ip: String, config: ChargeScriptGenerator.ChannelConfig): RpcResult<Unit> {
        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val existing = list.getOrNull()?.scripts?.firstOrNull { it.name == ChargeScriptGenerator.SUPERVISOR_SCRIPT_NAME }

        if (existing != null && existing.running) {
            val eval = rpcClient.scriptEval(ip, existing.id, ChargeScriptGenerator.evalUpsertChannel(config))
            eval.errorOrNull()?.let { return it }
            // Rattrapage des scripts déployés avant le 2026-08-22 en enable=true : couper
            // l'auto-démarrage sans arrêter le script (enable ne joue qu'au boot). Sans ça, un
            // redémarrage de l'appareil relancerait le script avec la config flash d'origine —
            // canaux périmés compris, qui couperaient une prise allumée manuellement dès 60 s
            // sous leur seuil (variante du bug de persistance, trouvée à l'audit du 2026-08-21).
            rpcClient.scriptSetConfig(ip, existing.id, enable = false)
            return RpcResult.Success(Unit)
        }

        // Pas d'éviction du relais ici, volontairement : ce script reste enable:false en
        // permanence (voir plus bas), il ne consomme donc jamais de slot dans la limite des 3
        // scripts activés — l'évincer avant sa création serait inutile (bug trouvé le 2026-09-01,
        // en creusant pourquoi le relais disparaissait sans raison lors d'un test de saturation).
        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, ChargeScriptGenerator.SUPERVISOR_SCRIPT_NAME)
            create.errorOrNull()?.let { return it }
            create.getOrNull()!!.id
        }
        if (existing != null) rpcClient.scriptStop(ip, scriptId)
        val topic = ntfyTopic()
        val code = ChargeScriptGenerator.generateSupervisor(
            listOf(config), selfId = scriptId, ntfyTopic = topic,
            ntfyCutoffBody = if (topic != null) context.getString(R.string.notif_cutoff_triggered) else "",
        )
        rpcClient.scriptPutCode(ip, scriptId, code).errorOrNull()?.let { return it }
        // enable=false (2026-08-22) : script transitoire — le minuteur natif qu'il surveille ne
        // survit pas à un redémarrage de l'appareil, lui non plus ne doit pas redémarrer au boot.
        // Avec enable=true, il repartait avec la config flash d'origine (canaux périmés compris)
        // et pouvait couper une prise allumée manuellement, sans aucun minuteur en cours.
        rpcClient.scriptSetConfig(ip, scriptId, enable = false).errorOrNull()?.let { return it }
        rpcClient.scriptStart(ip, scriptId).errorOrNull()?.let { return it }
        return RpcResult.Success(Unit)
    }

    /**
     * Retire [switchId] du suivi du script superviseur, sans toucher aux autres canaux. Ne fait
     * rien si le script n'existe pas ou n'est pas en cours d'exécution (rien à retirer) — s'il
     * n'a plus aucun canal après ce retrait, il se désactive lui-même au prochain top (voir
     * [ChargeScriptGenerator.generateSupervisor]), pas besoin de le faire depuis Hestia.
     */
    private suspend fun removeChargeSupervisorChannel(device: Device) {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        val existing = listResult.getOrNull()?.scripts?.firstOrNull { it.name == ChargeScriptGenerator.SUPERVISOR_SCRIPT_NAME }
        if (existing == null || !existing.running) return
        rpcClient.scriptEval(ip, existing.id, ChargeScriptGenerator.evalRemoveChannel(device.switchId))
    }

    /** Mémorise un minuteur en attente pour la notification de fin (voir [PendingTimer]). */
    private fun rememberPendingTimer(deviceId: Long, seconds: Int, detail: String?, thresholdW: Int?) {
        appPreferences.putPendingTimer(
            PendingTimer(deviceId, System.currentTimeMillis() + seconds * 1000L, detail.orEmpty(), thresholdW),
        )
    }

    /**
     * Vrai si ce canal a été coupé par **un script**, peu importe lequel — basé sur `source ==
     * "loopback"` (`Switch.GetStatus`), validé en direct le 2026-08-17. Remplace l'ancienne
     * détection (« le script de coupure a disparu/s'est désactivé »), qui ne se généralisait pas
     * à un script partagé entre plusieurs canaux : `source` est une propriété du canal lui-même,
     * indépendante du nombre de scripts sur l'appareil.
     */
    suspend fun cutoffScriptFired(device: Device): Boolean {
        val (_, result) = withIp(device) { ip -> rpcClient.getSwitchStatus(ip, device.switchId) }
        return result.getOrNull()?.source == "loopback"
    }

    /**
     * Annule le minuteur en **éteignant le canal** (ce qui annule le `toggle_after`), et retire ce
     * canal du script superviseur de coupure sur seuil s'il y figurait — les autres canaux qu'il
     * suit éventuellement restent inchangés.
     */
    suspend fun cancelTimer(device: Device): RpcResult<Unit> {
        removeChargeSupervisorChannel(device)
        removeTimerNotifyScript(device)
        val (_, set) = withIp(device) { i -> rpcClient.setSwitch(i, device.switchId, on = false) }
        return when (set) {
            is RpcResult.Success -> {
                appPreferences.removePendingTimer(device.id)
                RpcResult.Success(Unit)
            }
            is RpcResult.RpcError -> set
            is RpcResult.Failure -> set
        }
    }

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

    /**
     * Lit les plages de présence de **ce canal** depuis le script partagé de l'appareil (jamais
     * supposées). Privé depuis la fusion Planning/Présence (2026-08-18) : la présence est
     * désormais une simple variante de [Planning] ([Planning.isPresence]) — [getPlannings] est le
     * seul point d'entrée public, il fusionne les deux réalisations en une seule liste.
     *
     * Lit par `Script.Eval` si le script tourne (mémoire vivante, seule à jour dès qu'un canal a
     * été modifié sans redéploiement — voir [PresenceScriptGenerator]), sinon par le texte
     * enregistré (`Script.GetCode`, fiable uniquement dans ce cas puisque rien n'a pu diverger).
     */
    private suspend fun getPresenceWindows(device: Device): RpcResult<List<PresenceWindow>> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(emptyList())
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        listResult.errorOrNull()?.let { return it }
        val running = listResult.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME && it.running }

        val channels = if (running != null) {
            val json = rpcClient.scriptEval(ip, running.id, PresenceScriptGenerator.evalReadConfig()).getOrNull()?.result
            json?.let { PresenceScriptGenerator.parseChannels(it) }.orEmpty()
        } else {
            loadOrMigratePresenceScript(device).second.map { it.switchId to it.windows }
        }
        return RpcResult.Success(channels.firstOrNull { it.first == device.switchId }?.second.orEmpty())
    }

    /**
     * Déploie **l'ensemble des plages de ce canal** dans le script de présence partagé de
     * l'appareil, sans toucher aux autres canaux qu'il suit déjà. Liste vide → retire ce canal
     * ([stopPresence]). Neutralise d'abord un `auto_off` posé hors d'Hestia. Privé depuis la
     * fusion Planning/Présence — voir [createPlanning]/[updatePlanning]/[deletePlanning].
     */
    private suspend fun setPresenceWindows(device: Device, windows: List<PresenceWindow>): RpcResult<Unit> {
        if (windows.isEmpty()) return stopPresence(device)
        val (_, clear) = withIp(device) { i -> rpcClient.clearAutoOff(i, device.switchId) }
        clear.errorOrNull()?.let { return it }
        return applyPresenceChannel(device, PresenceScriptGenerator.ChannelConfig(device.switchId, device.name, windows))
    }

    /**
     * Retire **ce canal** du suivi du script de présence partagé — les autres canaux qu'il suit
     * éventuellement restent inchangés (jusqu'au 2026-08-18, ceci arrêtait et supprimait tout le
     * script, y compris pour un bloc multi-canaux : bug de portée corrigé par la mutualisation).
     */
    suspend fun stopPresence(device: Device): RpcResult<Unit> = applyPresenceChannel(device, null)

    /**
     * Coupe la présence de **ce canal pour aujourd'hui seulement** — la configuration permanente
     * (jours, horaires, marge) n'est pas touchée, la présence reprend normalement le lendemain.
     * Contrairement à [stopPresence] (retrait définitif, tous les jours), c'est l'action du
     * bouton ON/OFF pendant une présence en cours (demande de David, 2026-08-22) : « couper
     * Présence » se comprend comme « pas aujourd'hui », pas comme « supprimer la config ».
     * Mutation `Script.Eval` sur `STATE` uniquement (voir [PresenceScriptGenerator.evalStopToday]),
     * jamais sur `CFG` — même famille de mutation que le reste des lots de ce soir. Réaligne la
     * flash tout de suite après (voir [realignPresenceFlash]) : sans ça, la coupure du jour
     * n'existe qu'en mémoire vive et disparaît si le script redémarre pour une raison quelconque
     * (pas forcément une coupure secteur complète) — bug vécu en direct le 2026-08-22, la présence
     * repartait d'elle-même après une coupure censée tenir jusqu'au lendemain.
     */
    suspend fun stopPresenceForToday(device: Device): RpcResult<Unit> {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        listResult.errorOrNull()?.let { return it }
        val running = listResult.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME && it.running }
            ?: return RpcResult.Success(Unit) // rien en cours, rien à couper
        val eval = rpcClient.scriptEval(ip, running.id, PresenceScriptGenerator.evalStopToday(device.switchId))
        eval.errorOrNull()?.let { return it }
        realignPresenceFlash(ip, running.id)
        pushBlockedOffTodayToButtonTimer(ip, device)
        return RpcResult.Success(Unit)
    }

    /**
     * Suspend, pour aujourd'hui seulement, le blocage du minuteur bouton sur ce canal (voir
     * [ButtonTimerScriptGenerator.evalSetBlockedOffToday]) — pendant de [stopPresenceForToday]
     * côté bouton. Best-effort et silencieux : si le canal n'a pas de minuteur bouton configuré,
     * ou si son script n'est pas en cours d'exécution, il n'y a simplement rien à débloquer.
     */
    private suspend fun pushBlockedOffTodayToButtonTimer(ip: String, device: Device) = withContext(NonCancellable) {
        val listResult = rpcClient.scriptList(ip)
        val running = listResult.getOrNull()?.scripts?.firstOrNull { it.name == ButtonTimerScriptGenerator.SCRIPT_NAME && it.running }
            ?: return@withContext
        val eval = rpcClient.scriptEval(ip, running.id, ButtonTimerScriptGenerator.evalSetBlockedOffToday(device.switchId))
        if (eval.errorOrNull() != null) {
            logger.warn(DiagnosticLogger.RPC, "Déblocage bouton (aujourd'hui) @ $ip : Eval refusé")
            return@withContext
        }
        realignButtonTimerFlash(ip, running.id)
    }

    /**
     * Ajoute/remplace [config] pour son canal dans le script de présence partagé, ou retire ce
     * canal si [config] est nul — sans jamais toucher aux autres canaux déjà suivis. Si le script
     * tourne déjà, la mutation passe par `Script.Eval` (mémoire des autres canaux inchangée) ; sinon
     * (script absent, arrêté, ou reprise d'anciens scripts par canal), un premier déploiement
     * classique reprend les réglages déjà connus des autres canaux avant de tout réécrire — sans
     * risque puisqu'il n'y a alors rien de vivant à perdre.
     */
    private suspend fun applyPresenceChannel(device: Device, config: PresenceScriptGenerator.ChannelConfig?): RpcResult<Unit> {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        listResult.errorOrNull()?.let { return it }
        val running = listResult.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME && it.running }

        if (running != null) {
            val evalCode = if (config != null) {
                PresenceScriptGenerator.evalUpsertChannel(config)
            } else {
                PresenceScriptGenerator.evalRemoveChannel(device.switchId)
            }
            val eval = rpcClient.scriptEval(ip, running.id, evalCode)
            eval.errorOrNull()?.let { return it }
            // La mutation n'existe qu'en mémoire vive à ce stade : réaligner la flash tout de
            // suite, sinon le prochain redémarrage de l'appareil la ferait disparaître (bug de
            // fond vécu en direct le 2026-08-21, un planning supprimé ressuscité au rebranchement).
            realignPresenceFlash(ip, running.id)
            pushBlockedToButtonTimer(ip, device, config?.windows.orEmpty())
            return RpcResult.Success(Unit)
        }

        val (_, existing) = loadOrMigratePresenceScript(device)
        val others = existing.filterNot { it.switchId == device.switchId }
        val updated = if (config != null) others + config else others
        val result = deployPresenceScript(ip, updated)
        if (result is RpcResult.Success) pushBlockedToButtonTimer(ip, device, config?.windows.orEmpty())
        return result
    }

    /**
     * Pousse les plages interdites d'armement du minuteur bouton sur ce canal (voir
     * [ButtonTimerScriptGenerator.evalSetBlocked]) — pendant de [applyPresenceChannel] côté
     * bouton, seul point de synchronisation nécessaire puisque `createPlanning`/`updatePlanning`/
     * `deletePlanning`/`stopPresence` passent tous par lui. [windows] vide = plus aucune présence
     * sur ce canal, débloque totalement. Best-effort et silencieux : si le canal n'a pas de
     * minuteur bouton configuré, ou si son script n'est pas en cours d'exécution, il n'y a
     * simplement rien à bloquer pour l'instant — se corrigera au prochain passage par ici.
     */
    private suspend fun pushBlockedToButtonTimer(ip: String, device: Device, windows: List<PresenceWindow>) =
        withContext(NonCancellable) {
            val listResult = rpcClient.scriptList(ip)
            val running = listResult.getOrNull()?.scripts
                ?.firstOrNull { it.name == ButtonTimerScriptGenerator.SCRIPT_NAME && it.running }
                ?: return@withContext
            val eval = rpcClient.scriptEval(ip, running.id, ButtonTimerScriptGenerator.evalSetBlocked(device.switchId, windows))
            if (eval.errorOrNull() != null) {
                logger.warn(DiagnosticLogger.RPC, "Synchro blocage bouton @ $ip : Eval refusé")
                return@withContext
            }
            realignButtonTimerFlash(ip, running.id)
        }

    /**
     * Réécrit le texte enregistré (flash) du script de présence pour qu'il corresponde exactement
     * à sa mémoire vive — config **et** état, minuteurs de plages tirés au sort compris — puis le
     * redémarre : le script repart avec la mémoire qu'il avait (~1-2 s d'interruption, invisible
     * pour un tick à la minute). Mécanisme validé en direct le 2026-08-22 (Lot 1, y compris la
     * survie à une vraie coupure secteur). Sans ce réalignement, toute mutation `Eval` disparaît
     * au prochain redémarrage de l'appareil, le script relisant la flash au boot.
     *
     * Best-effort : un échec laisse le script tourner avec une flash en retard — pas pire
     * qu'avant ce correctif, et le prochain passage retente. [NonCancellable] : une fois commencé,
     * va au bout même si l'écran appelant se ferme (jamais un script laissé à l'arrêt parce qu'un
     * ViewModel a été détruit entre le Stop et le Start — leçon du bug de renommage ntfy).
     */
    private suspend fun realignPresenceFlash(ip: String, scriptId: Int) = withContext(NonCancellable) {
        val json = rpcClient.scriptEval(ip, scriptId, PresenceScriptGenerator.evalReadFull()).getOrNull()?.result
        val snapshot = json?.let { PresenceScriptGenerator.parseLiveSnapshot(it) }
        if (snapshot == null) {
            logger.warn(DiagnosticLogger.RPC, "Réalignement présence @ $ip : snapshot illisible, flash laissée en l'état")
            return@withContext
        }
        val (configs, stateJson) = snapshot
        if (configs.isEmpty()) {
            // Plus aucun canal suivi : suppression complète, comme deployPresenceScript avec une
            // liste vide — libère au passage un des 3 emplacements de script de l'appareil.
            rpcClient.scriptStop(ip, scriptId)
            rpcClient.scriptDelete(ip, scriptId)
            logger.info(DiagnosticLogger.RPC, "Script présence supprimé (plus aucun canal) @ $ip")
            return@withContext
        }
        val topic = ntfyTopic()
        val code = PresenceScriptGenerator.generateSupervisor(
            configs,
            ntfyTopic = topic,
            ntfyStartBody = if (topic != null) context.getString(R.string.notif_ntfy_presence_started) else "",
            ntfyEndBody = if (topic != null) context.getString(R.string.notif_ntfy_presence_ended) else "",
            initialStateJson = stateJson,
        )
        rpcClient.scriptStop(ip, scriptId).errorOrNull()?.let {
            logger.warn(DiagnosticLogger.RPC, "Réalignement présence @ $ip : Stop refusé, flash laissée en l'état")
            return@withContext
        }
        val put = rpcClient.scriptPutCode(ip, scriptId, code)
        if (put.errorOrNull() != null) {
            // Flash inchangée (ancienne version toujours valide) : on relance simplement le script.
            logger.warn(DiagnosticLogger.RPC, "Réalignement présence @ $ip : PutCode refusé, redémarrage sur l'ancien texte")
            rpcClient.scriptStart(ip, scriptId)
            return@withContext
        }
        rpcClient.scriptSetConfig(ip, scriptId, enable = true)
        rpcClient.scriptStart(ip, scriptId)
        logger.info(DiagnosticLogger.RPC, "Flash présence réalignée @ $ip (${configs.size} canal(aux))")
    }

    /**
     * Lit les canaux actuellement suivis par le script de présence partagé, en reprenant
     * d'éventuels scripts hérités (un par canal, avant le 2026-08-18) si le script partagé
     * n'existe pas encore — transparent pour l'utilisateur, fusionnés puis les anciens supprimés.
     */
    private suspend fun loadOrMigratePresenceScript(device: Device): Pair<String, List<PresenceScriptGenerator.ChannelConfig>> {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        val scripts = listResult.getOrNull()?.scripts.orEmpty()
        val allDevices = listOf(device) + siblingsSharingIp(device)

        val shared = scripts.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME }
        if (shared != null) {
            val code = rpcClient.scriptGetCode(ip, shared.id).getOrNull()?.data ?: return ip to emptyList()
            val configs = PresenceScriptGenerator.parseSupervisor(code).orEmpty().mapNotNull { (switchId, windows) ->
                val name = allDevices.firstOrNull { it.switchId == switchId }?.name ?: return@mapNotNull null
                PresenceScriptGenerator.ChannelConfig(switchId, name, windows)
            }
            return ip to configs
        }

        // Pas de script partagé : reprendre d'éventuels scripts hérités (ancien format, un par
        // canal), les fusionner dans le nouveau, puis nettoyer les anciens — best-effort, jamais
        // bloquant si un des scripts hérités est illisible (juste ignoré).
        val legacy = allDevices.mapNotNull { d ->
            val entry = scripts.firstOrNull { it.name == PresenceScriptGenerator.legacyScriptName(d.switchId) } ?: return@mapNotNull null
            val code = rpcClient.scriptGetCode(ip, entry.id).getOrNull()?.data ?: return@mapNotNull null
            val windows = PresenceScriptGenerator.parseLegacyChannel(code) ?: return@mapNotNull null
            entry to PresenceScriptGenerator.ChannelConfig(d.switchId, d.name, windows)
        }
        if (legacy.isEmpty()) return ip to emptyList()
        val migrated = legacy.map { it.second }
        deployPresenceScript(ip, migrated)
        legacy.forEach { (entry, _) ->
            rpcClient.scriptStop(ip, entry.id)
            rpcClient.scriptDelete(ip, entry.id)
        }
        return ip to migrated
    }

    /** Réécrit le script de présence partagé avec exactement [configs] ; le supprime si la liste est vide. */
    private suspend fun deployPresenceScript(ip: String, configs: List<PresenceScriptGenerator.ChannelConfig>): RpcResult<Unit> {
        val list = rpcClient.scriptList(ip)
        list.errorOrNull()?.let { return it }
        val existing = list.getOrNull()?.scripts?.firstOrNull { it.name == PresenceScriptGenerator.SCRIPT_NAME }

        if (configs.isEmpty()) {
            existing?.let {
                rpcClient.scriptStop(ip, it.id)
                rpcClient.scriptDelete(ip, it.id).errorOrNull()?.let { e -> return e }
            }
            return RpcResult.Success(Unit)
        }

        // Évince le relais avant toute transition vers enable:true — création comme réactivation
        // d'un script existant mais désactivé (jamais le cas normalement pour celui-ci, mais reste
        // défensif : voir DeviceRepository.evictSmokeRelayIfNeeded).
        if (existing == null || !existing.enable) evictSmokeRelayIfNeeded(ip, list.getOrNull()?.scripts.orEmpty())
        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, PresenceScriptGenerator.SCRIPT_NAME)
            create.errorOrNull()?.let { return it }
            create.getOrNull()!!.id
        }
        if (existing != null) rpcClient.scriptStop(ip, scriptId)
        val topic = ntfyTopic()
        // Plusieurs plages possibles par canal : le script ne sait pas, au moment où il bascule,
        // laquelle a déclenché — texte générique plutôt qu'un horaire qui serait celui de la
        // mauvaise plage (contrairement au planning, qui n'a qu'un seul créneau).
        val code = PresenceScriptGenerator.generateSupervisor(
            configs,
            ntfyTopic = topic,
            ntfyStartBody = if (topic != null) context.getString(R.string.notif_ntfy_presence_started) else "",
            ntfyEndBody = if (topic != null) context.getString(R.string.notif_ntfy_presence_ended) else "",
        )
        rpcClient.scriptPutCode(ip, scriptId, code).errorOrNull()?.let { return it }
        rpcClient.scriptSetConfig(ip, scriptId, enable = true).errorOrNull()?.let { return it }
        rpcClient.scriptStart(ip, scriptId).errorOrNull()?.let { return it }
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

        // Évince le relais avant toute transition vers enable:true — création comme réactivation
        // d'un script existant mais désactivé (jamais le cas normalement pour celui-ci, mais reste
        // défensif : voir DeviceRepository.evictSmokeRelayIfNeeded).
        if (existing == null || !existing.enable) evictSmokeRelayIfNeeded(ip, list.getOrNull()?.scripts.orEmpty())
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
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        val running = listResult.getOrNull()?.scripts?.firstOrNull { it.name == ButtonTimerScriptGenerator.SCRIPT_NAME && it.running }

        val mine = if (running != null) {
            // Script déjà en cours d'exécution : sa mémoire vivante est la seule source à jour,
            // le texte enregistré peut être en retard depuis un ajout/retrait via Script.Eval.
            val json = rpcClient.scriptEval(ip, running.id, ButtonTimerScriptGenerator.evalReadConfig()).getOrNull()?.result
            json?.let { ButtonTimerScriptGenerator.parseEvalResult(it) }.orEmpty().firstOrNull { it.first == device.switchId }
        } else {
            val (_, configs) = loadOrMigrateButtonTimerScript(device)
            configs.firstOrNull { it.switchId == device.switchId }?.let { Triple(it.switchId, it.durationSec, it.thresholdW) }
        }
        return mine?.let { ButtonTimerConfig(true, it.second, it.third) } ?: ButtonTimerConfig(false, DEFAULT_BUTTON_TIMER_SEC, null)
    }

    /**
     * Seuil de coupure actuellement surveillé sur ce canal par le script `hestia_charge`, ou null
     * s'il n'y en a pas (script absent, arrêté, ou canal non suivi) — jamais stocké, relu à
     * chaque appel. Coûte un `Script.List` (+ un `Script.Eval` seulement si le script tourne) :
     * réservé aux canaux « Actif » sans planning/présence/décompte connu (seul cas où ce seuil
     * serait sinon invisible, voir [kapoue.hestia.ui.screens.dashboard.DashboardViewModel]) —
     * jamais appelé pour tous les canaux à chaque cycle. Mécanisme validé en direct sur la
     * Strip4 le 2026-08-22.
     */
    suspend fun getActiveChargeThreshold(device: Device): Int? {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return null
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        val running = listResult.getOrNull()?.scripts
            ?.firstOrNull { it.name == ChargeScriptGenerator.SUPERVISOR_SCRIPT_NAME && it.running }
            ?: return null
        val json = rpcClient.scriptEval(ip, running.id, ChargeScriptGenerator.evalReadConfig()).getOrNull()?.result
            ?: return null
        return ChargeScriptGenerator.parseEvalResult(json).firstOrNull { it.first == device.switchId }?.second
    }

    /**
     * Même chose que [getActiveChargeThreshold], côté minuteur bouton : seuil du canal **si et
     * seulement si** il est actuellement armé (`STATE[i].armed`) — un canal configuré mais pas
     * armé (aucun appui bouton en cours) ne doit rien retourner, contrairement à
     * [getButtonTimerConfig] qui lit la config pour le **prochain** appui, armé ou non.
     */
    suspend fun getActiveButtonThreshold(device: Device): Int? {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return null
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        val running = listResult.getOrNull()?.scripts
            ?.firstOrNull { it.name == ButtonTimerScriptGenerator.SCRIPT_NAME && it.running }
            ?: return null
        val json = rpcClient.scriptEval(ip, running.id, ButtonTimerScriptGenerator.evalReadArmedThresholds()).getOrNull()?.result
            ?: return null
        return ButtonTimerScriptGenerator.parseArmedThresholds(json).firstOrNull { it.first == device.switchId }?.second
    }

    /**
     * Active/reconfigure ou désactive le minuteur déclenché par le bouton physique **de ce
     * canal**, sans jamais toucher aux autres canaux configurés du même appareil. Si le script
     * partagé tourne déjà, la modification passe par `Script.Eval` (mémoire des autres canaux
     * inchangée — voir [ButtonTimerScriptGenerator]) ; sinon (script absent, arrêté, ou reprise
     * d'anciens scripts par canal), un premier déploiement classique reprend les réglages déjà
     * connus des autres canaux avant de les réécrire tous ensemble, sans risque puisqu'il n'y a
     * alors rien de vivant à perdre. [durationSeconds] null = sans limite de durée ([thresholdW]
     * alors obligatoire, imposé côté appelant).
     */
    suspend fun setButtonTimer(device: Device, enabled: Boolean, durationSeconds: Int?, thresholdW: Int?): RpcResult<Unit> {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        listResult.errorOrNull()?.let { return it }
        val running = listResult.getOrNull()?.scripts?.firstOrNull { it.name == ButtonTimerScriptGenerator.SCRIPT_NAME && it.running }

        if (running != null) {
            val evalCode = if (enabled) {
                ButtonTimerScriptGenerator.evalUpsertChannel(
                    ButtonTimerScriptGenerator.ChannelConfig(device.switchId, durationSeconds, thresholdW, device.name),
                )
            } else {
                ButtonTimerScriptGenerator.evalRemoveChannel(device.switchId)
            }
            val eval = rpcClient.scriptEval(ip, running.id, evalCode)
            eval.errorOrNull()?.let { return it }
            // Même réalignement que la présence : sans lui, la mutation disparaît au prochain
            // redémarrage de l'appareil (voir realignPresenceFlash).
            realignButtonTimerFlash(ip, running.id)
            return RpcResult.Success(Unit)
        }

        val (_, existing) = loadOrMigrateButtonTimerScript(device)
        val others = existing.filterNot { it.switchId == device.switchId }
        val updated = if (enabled) {
            others + ButtonTimerScriptGenerator.ChannelConfig(device.switchId, durationSeconds, thresholdW, device.name)
        } else {
            others
        }
        return deployButtonTimerScript(ip, updated)
    }

    /** Pendant du réalignement de présence pour le minuteur bouton — voir [realignPresenceFlash]. */
    private suspend fun realignButtonTimerFlash(ip: String, scriptId: Int) = withContext(NonCancellable) {
        val json = rpcClient.scriptEval(ip, scriptId, ButtonTimerScriptGenerator.evalReadFull()).getOrNull()?.result
        val snapshot = json?.let { ButtonTimerScriptGenerator.parseLiveSnapshot(it) }
        if (snapshot == null) {
            logger.warn(DiagnosticLogger.RPC, "Réalignement bouton @ $ip : snapshot illisible, flash laissée en l'état")
            return@withContext
        }
        val (configs, stateJson) = snapshot
        if (configs.isEmpty()) {
            rpcClient.scriptStop(ip, scriptId)
            rpcClient.scriptDelete(ip, scriptId)
            logger.info(DiagnosticLogger.RPC, "Script bouton supprimé (plus aucun canal) @ $ip")
            return@withContext
        }
        val topic = ntfyTopic()
        val code = ButtonTimerScriptGenerator.generate(
            configs,
            ntfyTopic = topic,
            ntfyEndBody = if (topic != null) context.getString(R.string.notif_button_timer_ended) else "",
            ntfyCutoffBody = if (topic != null) context.getString(R.string.notif_cutoff_triggered) else "",
            initialStateJson = stateJson,
        )
        rpcClient.scriptStop(ip, scriptId).errorOrNull()?.let {
            logger.warn(DiagnosticLogger.RPC, "Réalignement bouton @ $ip : Stop refusé, flash laissée en l'état")
            return@withContext
        }
        val put = rpcClient.scriptPutCode(ip, scriptId, code)
        if (put.errorOrNull() != null) {
            logger.warn(DiagnosticLogger.RPC, "Réalignement bouton @ $ip : PutCode refusé, redémarrage sur l'ancien texte")
            rpcClient.scriptStart(ip, scriptId)
            return@withContext
        }
        rpcClient.scriptSetConfig(ip, scriptId, enable = true)
        rpcClient.scriptStart(ip, scriptId)
        logger.info(DiagnosticLogger.RPC, "Flash bouton réalignée @ $ip (${configs.size} canal(aux))")
    }


    // --- Planning (précis, Schedule natif ; ou simulation de présence, script partagé) ---

    /**
     * Lit les plannings réellement présents sur l'appareil, **précis et simulations de présence
     * confondus** (jamais supposés) — fusion Planning/Présence du 2026-08-18, un seul point
     * d'entrée pour les deux réalisations (voir [Planning]). Un planning **Unique** dont
     * l'échéance est passée est supprimé de l'appareil dans la foulée (pas de tâche de fond : le
     * nettoyage se fait à l'occasion de la prochaine lecture) — n'arrive jamais pour une
     * simulation de présence, qui ne peut pas être Unique.
     */
    suspend fun getPlannings(device: Device): RpcResult<List<Planning>> {
        // Appareils démo : aucun réseau (évite un timeout par tuile fictive à chaque relevé).
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(emptyList())
        val (ip, r) = withIp(device) { i -> rpcClient.scheduleList(i) }
        val native = when (r) {
            is RpcResult.Success -> {
                val all = reconstructPlannings(r.value.jobs, device.switchId)
                val (expired, active) = all.partition { it.isExpiredOnce() }
                if (expired.isNotEmpty()) {
                    logger.info(DiagnosticLogger.RPC, "Nettoyage de ${expired.size} planning(s) Unique expiré(s)")
                    expired.forEach { p ->
                        p.onJobId?.let { rpcClient.scheduleDelete(ip, it) }
                        p.offJobId?.let { rpcClient.scheduleDelete(ip, it) }
                        p.cutoffScriptId?.let { rpcClient.scriptStop(ip, it); rpcClient.scriptDelete(ip, it) }
                    }
                }
                // Seuil de coupure relu à part (un appel Script.GetCode par planning concerné) :
                // jamais stocké, toujours l'état réel du script sur l'appareil.
                active.map { p ->
                    val scriptId = p.cutoffScriptId ?: return@map p
                    val code = rpcClient.scriptGetCode(ip, scriptId).getOrNull()?.data ?: return@map p
                    p.copy(cutoffThresholdW = ChargeScriptGenerator.parseThreshold(code))
                }
            }
            is RpcResult.RpcError -> return r
            is RpcResult.Failure -> return r
        }
        if (!device.hasScripting) return RpcResult.Success(native)
        val presence = getPresenceWindows(device).getOrNull().orEmpty().map { w ->
            Planning(
                startHour = w.startHour, startMinute = w.startMinute,
                endHour = w.endHour, endMinute = w.endMinute,
                days = w.days, marginMinutes = w.marginMinutes,
            )
        }
        return RpcResult.Success((native + presence).sortedWith(compareBy({ it.startMinutes }, { it.endMinutes })))
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
     * Crée un planning après contrôle de conflit (contre tous les plannings existants, précis ou
     * simulations de présence — [getPlannings] renvoie désormais les deux confondus) et de limite
     * atteinte. [date] non nul = planning **Unique** (une seule occurrence, à cette date précise) ;
     * [days] est alors ignoré. Le contrôle de chevauchement d'un Unique se fait sur le jour de
     * semaine de [date] : deux Uniques au même jour de semaine mais à des dates différentes
     * peuvent donc se signaler comme en conflit à tort (cas rare, accepté pour ne pas
     * complexifier le contrôle).
     *
     * [cutoffThresholdW] : coupure sur seuil de consommation — planning **précis** uniquement
     * (Unique ou récurrent). Déploie un script dédié à ce planning (jamais partagé, jamais
     * réutilisé par un autre minuteur ou planning). Pour un récurrent, le script se réarme
     * proprement à chaque occurrence (`Script.Start` après un `Script.Stop` réexécute le script
     * depuis le début, aucun état résiduel — validé en direct).
     *
     * [marginMinutes] non nul = **simulation de présence** plutôt que planning précis : jamais
     * Unique, jamais de coupure sur seuil (mutuellement exclusif, décidé le 2026-08-18 — l'appelant
     * ne doit jamais fournir les deux à la fois, ignoré ici par construction si c'était le cas).
     * Réalisée par le script de présence de ce canal plutôt que par Schedule natif — ne compte pas
     * dans [MAX_PLANNINGS], qui ne concerne que les programmes cron.
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
        marginMinutes: Int? = null,
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
        if (marginMinutes == null && existing.count { !it.isPresence } >= MAX_PLANNINGS) {
            return CreatePlanningResult.LimitReached
        }

        val startMin = startHour * 60 + startMinute
        val endMin = endHour * 60 + endMinute
        val conflictDays = date?.let { setOf(cronDayOf(it)) } ?: days
        val newIntervals = ScheduleCodec.weeklyIntervals(startMin, endMin, conflictDays)
        existing.firstOrNull { p ->
            ScheduleCodec.intervalsOverlap(newIntervals, ScheduleCodec.weeklyIntervals(p.startMinutes, p.endMinutes, p.conflictDays()))
        }?.let { return CreatePlanningResult.Conflict(it) }

        // getPlannings ci-dessus vient de réussir : l'emplacement IP qui fonctionne est à jour.
        val ip = currentIp(device)

        if (marginMinutes != null) {
            // Déjà lues via getPlannings ci-dessus (existing) — inutile de rappeler
            // getPresenceWindows, qui referait le même aller-retour RPC pour rien.
            val windows = existing.filter { it.isPresence }.map { it.toPresenceWindow() }
            val newWindow = PresenceWindow(startHour, startMinute, endHour, endMinute, marginMinutes, days)
            return when (setPresenceWindows(device, windows + newWindow)) {
                is RpcResult.Success -> {
                    // Une présence tout juste (re)créée n'a jamais été désactivée pour aujourd'hui
                    // — sans ça, une présence supprimée puis recréée restait marquée « désactivée »
                    // indéfiniment (bug vécu en direct par David le 2026-08-22).
                    appPreferences.clearPresenceDisabledToday(device.id)
                    CreatePlanningResult.Success
                }
                else -> CreatePlanningResult.Error
            }
        }

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
        applyIfAlreadyActive(ip, device, startHour, startMinute, endHour, endMinute, days, date)
        // Même raison que côté présence ci-dessus : un planning tout juste créé/modifié n'a
        // jamais été désactivé pour aujourd'hui. Sans effet pour un Unique (jamais marqué désactivé).
        appPreferences.clearPlanningDisabledToday(device.id)
        return CreatePlanningResult.Success
    }

    /**
     * Un planning précis dont la fenêtre couvre déjà l'instant de sa création (ou modification)
     * ne s'applique **jamais** tout seul : le programme cron ne déclenche qu'à sa prochaine
     * occurrence (demain, si l'heure de départ du jour est déjà passée) — jamais rétroactivement.
     * Sans ce rattrapage, la tuile affichait « Planifié » (l'heure du téléphone tombe dans le
     * créneau) alors que la prise, elle, n'avait jamais reçu l'ordre — bug vécu en direct par
     * David le 2026-08-22 (pire encore pour un Unique créé après son heure de départ : le
     * programme ne se déclenche alors plus *jamais*, une seule occurrence ratée pour toujours).
     * Best-effort, ne fait jamais échouer la création/modification elle-même si ce rattrapage rate.
     */
    private suspend fun applyIfAlreadyActive(
        ip: String,
        device: Device,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        days: Set<Int>,
        date: LocalDate?,
    ) {
        val nowActive = Planning(startHour, startMinute, endHour, endMinute, days, date = date).isActiveNow()
        if (nowActive) rpcClient.setSwitch(ip, device.switchId, on = true)
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
        // Pas d'éviction du relais ici, volontairement : ce script reste enable:false en
        // permanence (voir plus bas), il ne consomme donc jamais de slot dans la limite des 3
        // scripts activés — même raison que ChargeScriptGenerator.SUPERVISOR_SCRIPT_NAME, voir
        // upsertChargeSupervisorChannel.
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
     * Modifie un planning : contrôle de conflit (en s'excluant lui-même), puis remplace sa
     * réalisation sur l'appareil. On **crée d'abord** la nouvelle, on **supprime ensuite**
     * l'ancienne : si le réseau lâche en cours, on risque au pire un doublon (récupérable), jamais
     * une perte. [marginMinutes] peut changer par rapport à [old] : passer d'un planning précis à
     * une simulation de présence (ou l'inverse) fonctionne, en basculant proprement de mécanisme
     * (Schedule natif ↔ script de présence).
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
        marginMinutes: Int? = null,
    ): CreatePlanningResult {
        if (date != null && !onceEndAt(startHour, startMinute, endHour, endMinute, date).isAfter(LocalDateTime.now())) {
            return CreatePlanningResult.PastOnce
        }
        val existing = when (val r = getPlannings(device)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return CreatePlanningResult.Error
            is RpcResult.Failure -> return CreatePlanningResult.Error
        }
        // Un passage présence → précis consomme un nouveau programme cron : à vérifier contre la
        // limite (jamais nécessaire dans les autres cas, le compte de programmes cron ne peut pas
        // augmenter : précis → précis remplace à l'identique, tout le reste libère un programme).
        if (marginMinutes == null && old.isPresence && existing.count { !it.isPresence } >= MAX_PLANNINGS) {
            return CreatePlanningResult.LimitReached
        }
        val startMin = startHour * 60 + startMinute
        val endMin = endHour * 60 + endMinute
        // Conflit avec les AUTRES plannings uniquement (on s'exclut soi-même).
        val conflictDays = date?.let { setOf(cronDayOf(it)) } ?: days
        val newIntervals = ScheduleCodec.weeklyIntervals(startMin, endMin, conflictDays)
        existing.filterNot { it.isSameEntryAs(old) }
            .firstOrNull { p ->
                ScheduleCodec.intervalsOverlap(newIntervals, ScheduleCodec.weeklyIntervals(p.startMinutes, p.endMinutes, p.conflictDays()))
            }
            ?.let { return CreatePlanningResult.Conflict(it) }

        // getPlannings ci-dessus vient de réussir : l'emplacement IP qui fonctionne est à jour.
        val ip = currentIp(device)

        if (marginMinutes != null) {
            // Déjà lues via getPlannings ci-dessus (existing) — inutile de rappeler
            // getPresenceWindows, qui referait le même aller-retour RPC pour rien.
            val windows = existing.filter { it.isPresence }.map { it.toPresenceWindow() }
            val kept = if (old.isPresence) windows.filterNot { it.matchesPlanning(old) } else windows
            val newWindow = PresenceWindow(startHour, startMinute, endHour, endMinute, marginMinutes, days)
            val result = setPresenceWindows(device, kept + newWindow)
            if (result !is RpcResult.Success) return CreatePlanningResult.Error
            // Bascule précis → présence : retirer l'ancienne réalisation cron.
            if (!old.isPresence) {
                old.onJobId?.let { rpcClient.scheduleDelete(ip, it) }
                old.offJobId?.let { rpcClient.scheduleDelete(ip, it) }
                old.cutoffScriptId?.let { rpcClient.scriptStop(ip, it); rpcClient.scriptDelete(ip, it) }
            }
            appPreferences.clearPresenceDisabledToday(device.id)
            return CreatePlanningResult.Success
        }

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
        // Nouveaux programmes en place : retirer l'ancienne réalisation.
        if (old.isPresence) {
            // Bascule présence → précis : retirer l'ancienne plage du script de présence. Déjà lues
            // via getPlannings en haut de fonction (existing), rien n'a changé côté présence depuis.
            val windows = existing.filter { it.isPresence }.map { it.toPresenceWindow() }
            setPresenceWindows(device, windows.filterNot { it.matchesPlanning(old) })
        } else {
            old.onJobId?.let { rpcClient.scheduleDelete(ip, it) }
            old.offJobId?.let { rpcClient.scheduleDelete(ip, it) }
            old.cutoffScriptId?.let { rpcClient.scriptStop(ip, it); rpcClient.scriptDelete(ip, it) }
        }
        applyIfAlreadyActive(ip, device, startHour, startMinute, endHour, endMinute, days, date)
        // Même raison que côté présence ci-dessus : un planning tout juste créé/modifié n'a
        // jamais été désactivé pour aujourd'hui. Sans effet pour un Unique (jamais marqué désactivé).
        appPreferences.clearPlanningDisabledToday(device.id)
        return CreatePlanningResult.Success
    }

    /**
     * Vrai si [this] et [other] sont la **même** entrée — pour s'exclure soi-même d'un contrôle de
     * chevauchement lors d'une modification. Un planning précis se reconnaît par ses identifiants
     * de programme cron ; une simulation de présence n'en a pas (rien à adresser individuellement
     * côté script), on la reconnaît donc par son contenu exact.
     */
    private fun Planning.isSameEntryAs(other: Planning): Boolean = if (other.isPresence) {
        isPresence && startHour == other.startHour && startMinute == other.startMinute &&
            endHour == other.endHour && endMinute == other.endMinute &&
            days == other.days && marginMinutes == other.marginMinutes
    } else {
        onJobId == other.onJobId && offJobId == other.offJobId
    }

    /** Vrai si cette plage de présence correspond exactement à ce [Planning] (voir [isSameEntryAs]). */
    private fun PresenceWindow.matchesPlanning(p: Planning): Boolean =
        startHour == p.startHour && startMinute == p.startMinute &&
            endHour == p.endHour && endMinute == p.endMinute &&
            days == p.days && marginMinutes == p.marginMinutes

    /**
     * Reconvertit un [Planning] de présence (tel que renvoyé par [getPlannings]) en [PresenceWindow]
     * — évite de rappeler [getPresenceWindows] (un aller-retour RPC) alors qu'on vient déjà de lire
     * ce canal via [getPlannings]. Uniquement valable sur un [Planning.isPresence].
     */
    private fun Planning.toPresenceWindow(): PresenceWindow =
        PresenceWindow(startHour, startMinute, endHour, endMinute, marginMinutes ?: 0, days)

    /** Jour de semaine cron (0 = dimanche … 6 = samedi) d'une date. */
    private fun cronDayOf(date: LocalDate): Int = date.dayOfWeek.value % 7

    /** Jours utilisables pour un contrôle de chevauchement : jour de semaine de sa date pour un
     * planning Unique (dont [Planning.days] est vide), [Planning.days] tel quel sinon. */
    private fun Planning.conflictDays(): Set<Int> = date?.let { setOf(cronDayOf(it)) } ?: days

    /**
     * Supprime un planning — ses deux programmes cron (et son éventuel script de coupure dédié)
     * pour un planning précis, ou sa plage dans le script de présence partagé du canal pour une
     * simulation de présence. Si le planning est **en cours** (créneau actif), on **éteint** la
     * prise dans la foulée : supprimer l'allumeur sans éteindre laisserait la prise allumée sans
     * extinction prévue.
     */
    suspend fun deletePlanning(device: Device, planning: Planning): RpcResult<Unit> {
        if (planning.isPresence) {
            val windows = getPresenceWindows(device).getOrNull().orEmpty()
            val result = setPresenceWindows(device, windows.filterNot { it.matchesPlanning(planning) })
            if (result is RpcResult.Success && planning.isActiveNow()) {
                val (_, set) = withIp(device) { i -> rpcClient.setSwitch(i, device.switchId, on = false) }
                set.errorOrNull()?.let { return it }
            }
            return result
        }
        val onJobId = planning.onJobId ?: return RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE)
        val offJobId = planning.offJobId ?: return RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE)
        val (ip, first) = withIp(device) { i -> rpcClient.scheduleDelete(i, onJobId) }
        first.errorOrNull()?.let { return it }
        rpcClient.scheduleDelete(ip, offJobId).errorOrNull()?.let { return it }
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
                    marginMinutes = planning.marginMinutes,
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
            days, date, paused.cutoffThresholdW, paused.marginMinutes,
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

    // --- Cloud Shelly (opt-in, désactivé par défaut — voir CLAUDE.md) ---

    /**
     * Lit l'état cloud réel de l'appareil physique : jamais mémorisé par Hestia, toujours relu.
     * Combine `Cloud.GetConfig` (activé + serveur assigné), `Cloud.GetStatus` (connecté) et
     * `Shelly.GetDeviceInfo` (MAC, affiché comme « Cloud ID »).
     */
    suspend fun getCloudInfo(device: Device): CloudInfo {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return CloudInfo.Unavailable
        val (ip, configResult) = withIp(device) { i -> rpcClient.cloudGetConfig(i) }
        val config = configResult.getOrNull() ?: return CloudInfo.Unavailable
        val connected = rpcClient.cloudGetStatus(ip).getOrNull()?.connected ?: false
        val macId = rpcClient.getDeviceInfo(ip).getOrNull()?.mac
        return CloudInfo.Available(
            enabled = config.enable,
            connected = connected,
            server = config.server,
            macId = macId,
        )
    }

    /**
     * Met en cache le MAC (« Cloud ID ») sur tous les canaux d'un même appareil physique — lu une
     * fois en local, il reste disponible ensuite même hors réseau, pour le repli cloud (lot 3).
     * N'écrit que les canaux dont la valeur a effectivement changé.
     */
    suspend fun cacheCloudId(members: List<Device>, macId: String) {
        for (device in members) {
            if (device.cloudId != macId) deviceDao.update(device.copy(cloudId = macId))
        }
    }

    /** Active/désactive le canal Cloud du firmware. Toujours à la demande explicite de l'utilisateur. */
    suspend fun setCloudEnabled(device: Device, enabled: Boolean): RpcResult<Unit> {
        if (device.ipAddress.startsWith(DEMO_IP_PREFIX)) return RpcResult.Success(Unit)
        val (_, result) = withIp(device) { ip -> rpcClient.cloudSetConfig(ip, enabled) }
        return when (result) {
            is RpcResult.Success -> RpcResult.Success(Unit)
            is RpcResult.RpcError -> result
            is RpcResult.Failure -> result
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

    /**
     * Ajoute un détecteur de fumée — sans sonde préalable, contrairement à [addChannels] : cet
     * appareil dort la majeure partie du temps (voir SMOKE-DETECTOR.md), un test de connexion à
     * l'ajout échouerait presque toujours pour rien. L'IP saisie par l'utilisateur est prise
     * telle quelle, vérifiée au premier contact réel (comme pour toute autre RPC). Pas de relais
     * (`supportsSwitch = false`), pas de script (`hasScripting = false` — présence/planning/
     * minuteur bouton ne s'appliquent pas à ce type), pas de mesure de puissance.
     * @return false si ce canal (IP, switchId 0) existe déjà.
     */
    suspend fun addSmokeDetector(name: String, ip: String): Boolean {
        if (deviceDao.exists(ip, 0)) return false
        deviceDao.insert(
            Device(
                name = name,
                deviceName = name,
                ipAddress = ip,
                switchId = 0,
                type = DeviceType.SMOKE_DETECTOR,
                supportsSwitch = false,
                hasScripting = false,
                hasPowerMetering = false,
                position = deviceDao.maxPosition() + 1,
            ),
        )
        logger.info(DiagnosticLogger.DB, "Ajout détecteur de fumée $ip")
        return true
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
     *
     * Lancé sur [appScope], pas sur l'appelant — même raison que [resyncDeviceName] : ça peut
     * enchaîner de nombreux allers-retours RPC (tous les appareils, tous leurs plannings), pas
     * question que ça s'arrête net si l'utilisateur quitte l'écran Réglages entre-temps.
     */
    fun resyncNtfyForAllDevices() {
        appScope.launch {
            for (device in getDevicesOnce()) {
                if (resyncNtfyForDevice(device)) appPreferences.markNtfySynced(device.id)
            }
        }
    }

    /**
     * Redéploie (planning, présence, minuteur bouton) avec le nom actuel de l'appareil — le nom
     * affiché dans les notifications ntfy est écrit en dur dans les scripts au moment de leur
     * déploiement, pas relu dynamiquement par la prise. Sans ce réappel après un renommage, les
     * notifications garderaient l'ancien nom indéfiniment. Best-effort : si l'appareil est
     * injoignable au moment du renommage, l'ancien nom reste dans les scripts jusqu'à la prochaine
     * modification qui les redéploie (limite connue, non résolue automatiquement pour l'instant).
     *
     * Lancé sur [appScope], pas sur l'appelant : ceci peut enchaîner plusieurs allers-retours RPC
     * (un par planning), et l'écran Modifier se ferme aussitôt après un renommage réussi — un
     * `viewModelScope.launch` classique se serait fait tuer par la destruction du ViewModel avant
     * la fin, laissant l'ancien nom dans les notifications malgré une attente de plusieurs minutes
     * (bug vécu en direct le 2026-08-19).
     */
    fun resyncDeviceName(device: Device) {
        appScope.launch { resyncNtfyForDevice(device) }
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
            // getPlannings fusionne plannings précis et simulations de présence : ce seul relevé
            // couvre désormais les deux (plus besoin d'un second passage par getPresenceWindows).
            when (val r = getPlannings(device)) {
                is RpcResult.Success -> for (p in r.value) {
                    updatePlanning(device, p, p.startHour, p.startMinute, p.endHour, p.endMinute, p.days, p.date, p.cutoffThresholdW, p.marginMinutes)
                }
                is RpcResult.RpcError, is RpcResult.Failure -> reachable = false
            }
        }
        if (device.hasScripting) {
            if (reachable) {
                val buttonConfig = getButtonTimerConfig(device)
                if (buttonConfig.enabled) {
                    setButtonTimer(device, true, buttonConfig.durationSeconds, buttonConfig.thresholdW)
                }
            }
        }
        return reachable
    }

    // --- Relais ntfy pour les détecteurs de fumée (Lot 4a/4b, voir SMOKE-DETECTOR.md) ---

    /**
     * Libère un slot en évinçant `hestia_smoke_relay` si l'appareil est déjà saturé — **jamais
     * l'inverse** : un vrai réglage métier (présence, minuteur bouton, coupure sur seuil) a
     * toujours priorité sur le relais, qui n'est qu'un confort ajouté par Hestia pour un besoin
     * qui n'est pas le sien (Lot 4b, retour David 2026-09-01 : « il ne faut jamais que
     * l'utilisateur soit bloqué dans l'usage de sa prise à cause d'un besoin technique pour le
     * détecteur »). À appeler juste avant de créer un **nouveau** script métier sur [ip], à partir
     * de [scripts] déjà lu par l'appelant (pas de second appel réseau). Ne fait rien si le relais
     * est absent ou si l'appareil a encore de la place — jamais d'éviction inutile.
     */
    private suspend fun evictSmokeRelayIfNeeded(ip: String, scripts: List<ScriptEntry>) {
        if (scripts.count { it.enable } < MAX_ENABLED_SCRIPTS_PER_DEVICE) return
        val relay = scripts.firstOrNull { it.name == SmokeRelayScriptGenerator.SCRIPT_NAME } ?: return
        rpcClient.scriptStop(ip, relay.id)
        rpcClient.scriptDelete(ip, relay.id)
        logger.info(DiagnosticLogger.RPC, "Relais détecteur de fumée évincé sur $ip (place nécessaire pour un vrai réglage)")
    }

    /**
     * Recalcule et repousse la couverture du relais ntfy pour tous les détecteurs de fumée connus
     * — script relais déployé de façon **opportuniste** sur jusqu'à [MAX_RELAY_TARGETS] appareils
     * scriptables ayant de la place (jamais un appareil désigné à l'avance, voir
     * `SmokeRelayScriptGenerator`), puis `Webhook.Create` reposé sur chaque détecteur joignable
     * avec la liste actuelle. Best-effort et silencieux : un appareil injoignable ou saturé est
     * simplement ignoré, sans erreur visible côté utilisateur.
     *
     * Mêmes déclencheurs que [resyncNtfyForAllDevices] (activation/sujet ntfy), en plus de l'ajout
     * d'un détecteur — jamais de tâche de fond dédiée. Un détecteur endormi au moment de l'appel
     * sera rattrapé de lui-même à son prochain réveil détecté par le Tableau (voir
     * [smokeWebhookCatchUpIfNeeded]).
     */
    fun resyncSmokeRelay() {
        appScope.launch { resyncSmokeRelayInternal() }
    }

    /**
     * Corrige directement le bandeau de couverture zéro (Lot 4c) quand un relevé ponctuel (ex.
     * [isSmokeRelay] depuis Réglages) trouve un relais bien vivant sans passer par une vraie
     * resynchronisation — un script relais `enable:true` survit à une coupure de courant/réseau
     * et peut donc être retrouvé alors que le dernier résultat connu datait d'avant (retour David,
     * 2026-09-03). Ne sert jamais à faire *apparaître* le bandeau, seulement à le faire
     * disparaître plus tôt qu'attendu.
     */
    fun confirmSmokeRelayCoverage() {
        appPreferences.setSmokeRelayCoverageOk(true)
    }

    /**
     * Vrai si [device] héberge actuellement le script relais — pour le picto de Réglages (Lot 4b).
     * Relu à chaque fois, jamais mémorisé (peut changer à tout moment par éviction ou
     * redistribution) : un seul `Script.List`, valable pour n'importe quel canal d'un même bloc
     * physique (même IP, script partagé).
     */
    suspend fun isSmokeRelay(device: Device): Boolean {
        val (_, result) = withIp(device) { ip -> rpcClient.scriptList(ip) }
        return result.getOrNull()?.scripts?.any { it.name == SmokeRelayScriptGenerator.SCRIPT_NAME } == true
    }

    /**
     * Rattrapage best-effort : appelé par le Tableau dès qu'un détecteur répond en local (donc
     * réveillé, la seule fenêtre où poser un webhook a un sens) — même principe que
     * [ntfyCatchUpIfNeeded], ne fait rien s'il est déjà à jour pour la génération ntfy courante.
     */
    suspend fun smokeWebhookCatchUpIfNeeded(device: Device) {
        if (appPreferences.isSmokeRelaySynced(device.id)) return
        resyncSmokeRelayInternal()
        appPreferences.markSmokeRelaySynced(device.id)
    }

    private suspend fun resyncSmokeRelayInternal() {
        val all = getDevicesOnce()
        val detectors = all.filter { it.type == DeviceType.SMOKE_DETECTOR }
        if (detectors.isEmpty()) return

        val topic = if (appPreferences.ntfyEnabled.value) appPreferences.ntfyTopic.value else null
        val bodies = SmokeRelayScriptGenerator.Bodies(
            alarm = context.getString(R.string.sensor_relay_body_alarm),
            alarmOff = context.getString(R.string.sensor_relay_body_alarm_off),
            alarmTest = context.getString(R.string.sensor_relay_body_alarm_test),
        )
        val names = detectors.mapNotNull { d ->
            d.cloudId?.let { mac -> SmokeRelayScriptGenerator.DeviceName(mac.uppercase(), d.name) }
        }

        // Un candidat par appareil physique (même IP), pas par canal : le moteur de scripts Shelly
        // est partagé par appareil, pas par canal (voir ButtonTimerScriptGenerator).
        val candidates = all.filter { it.hasScripting }.groupBy { it.ipAddress }.map { (_, members) -> members.first() }

        val relayTargets = mutableListOf<Pair<String, Int>>()
        for (device in candidates) {
            if (relayTargets.size >= MAX_RELAY_TARGETS) break
            deploySmokeRelay(device, topic, names, bodies)?.let { relayTargets += it }
        }
        // Bandeau de couverture zéro (Lot 4c) : dernier résultat connu, pas recalculé à l'ouverture
        // de l'app — seulement à chaque resynchronisation réelle comme celle-ci.
        appPreferences.setSmokeRelayCoverageOk(relayTargets.isNotEmpty())

        for (detector in detectors) {
            pushSmokeWebhooks(detector, relayTargets)
        }
    }

    /**
     * Déploie ou met à jour `hestia_smoke_relay` sur [device] si possible : mise à jour ciblée par
     * `Script.Eval` si déjà vivant (mémoire des autres détecteurs déjà relayés inchangée), premier
     * déploiement sinon — mais **jamais** si l'appareil est déjà saturé (3 scripts actifs, cf.
     * [MAX_ENABLED_SCRIPTS_PER_DEVICE]) sans que ce script y soit déjà : toujours évictable par un vrai
     * réglage métier, jamais l'inverse.
     * @return (ip, id du script) si prêt à recevoir des appels, sinon `null` (injoignable ou saturé).
     */
    private suspend fun deploySmokeRelay(
        device: Device,
        topic: String?,
        names: List<SmokeRelayScriptGenerator.DeviceName>,
        bodies: SmokeRelayScriptGenerator.Bodies,
    ): Pair<String, Int>? {
        val (ip, listResult) = withIp(device) { i -> rpcClient.scriptList(i) }
        val scripts = listResult.getOrNull()?.scripts ?: return null
        val existing = scripts.firstOrNull { it.name == SmokeRelayScriptGenerator.SCRIPT_NAME }
        if (existing == null && scripts.count { it.enable } >= MAX_ENABLED_SCRIPTS_PER_DEVICE) return null

        val scriptId = existing?.id ?: run {
            val create = rpcClient.scriptCreate(ip, SmokeRelayScriptGenerator.SCRIPT_NAME)
            create.getOrNull()?.id ?: return null
        }

        if (existing == null || !existing.running) {
            val code = SmokeRelayScriptGenerator.generate(topic, names, bodies)
            rpcClient.scriptPutCode(ip, scriptId, code).errorOrNull()?.let { return null }
            rpcClient.scriptSetConfig(ip, scriptId, enable = true)
            rpcClient.scriptStart(ip, scriptId).errorOrNull()?.let { return null }
        } else {
            rpcClient.scriptEval(ip, scriptId, SmokeRelayScriptGenerator.evalSetTopic(topic))
            rpcClient.scriptEval(ip, scriptId, SmokeRelayScriptGenerator.evalSetBodies(bodies))
            for (n in names) rpcClient.scriptEval(ip, scriptId, SmokeRelayScriptGenerator.evalUpsertName(n.mac, n.name))
        }
        return ip to scriptId
    }

    /**
     * Repose les 3 webhooks natifs (un par événement) sur [detector], visant [relayTargets] —
     * repart de zéro à chaque resynchronisation (supprime d'abord tout webhook déjà posé par
     * Hestia, reconnu par son nom). Ne fait rien si le détecteur est injoignable ou sans MAC en
     * cache ([Device.cloudId], voir [getLocalSensorStatus]) : réessaiera à son prochain réveil.
     */
    private suspend fun pushSmokeWebhooks(detector: Device, relayTargets: List<Pair<String, Int>>) {
        val mac = detector.cloudId?.uppercase() ?: return
        val (ip, listResult) = withIp(detector) { i -> rpcClient.webhookList(i) }
        val existing = listResult.getOrNull()?.hooks ?: return
        for (hook in existing.filter { it.name == SMOKE_WEBHOOK_NAME }) {
            rpcClient.webhookDelete(ip, hook.id)
        }
        if (relayTargets.isEmpty()) return

        for (event in SMOKE_WEBHOOK_EVENTS) {
            val urls = relayTargets.map { (relayIp, scriptId) ->
                val code = java.net.URLEncoder.encode("notifySmoke(\"$event\",\"$mac\")", "UTF-8")
                "http://$relayIp/rpc/Script.Eval?id=$scriptId&code=$code"
            }
            rpcClient.webhookCreate(ip, cid = 0, event = event, name = SMOKE_WEBHOOK_NAME, urls = urls)
        }
    }

    // --- Coupure de prise en cas d'alarme (Lot 5, voir SMOKE-DETECTOR.md) ---

    /** Une cible de coupure : adresse IP + canal (`switchId`) d'une prise à éteindre. */
    data class SmokeCutoffTarget(val ip: String, val switchId: Int)

    /**
     * État de la config de coupure d'un détecteur : [Unknown] = jamais lue avec succès (détecteur
     * endormi la plupart du temps, à distinguer d'une vraie liste vide — sinon l'écran affiche
     * « désactivé » alors qu'on n'en sait simplement rien, retour David 2026-09-03 : « ça laisse
     * penser que la config n'est pas passée »). [Configured] = lue avec succès, éventuellement vide.
     */
    sealed interface SmokeCutoffState {
        data object Unknown : SmokeCutoffState
        data class Configured(val targets: List<SmokeCutoffTarget>) : SmokeCutoffState
    }

    /**
     * État de coupure actuellement configuré sur [detector] — relu directement depuis ses
     * webhooks natifs (`Webhook.List`), **jamais stocké côté Hestia** (principe du projet : aucune
     * configuration d'appareil en propre). [SmokeCutoffState.Unknown] si injoignable à cet instant
     * (détecteur endormi) : pas d'appel réseau visible tant qu'on ne l'a pas réveillé, très
     * différent de « coupure désactivée ». Un seul hook suffit à connaître la liste actuelle : tous
     * les événements câblés partagent exactement les mêmes URLs (voir [setSmokeCutoffTargets]).
     */
    suspend fun getSmokeCutoffState(detector: Device): SmokeCutoffState {
        val (_, result) = withIp(detector) { ip -> rpcClient.webhookList(ip) }
        val hooks = result.getOrNull()?.hooks ?: return SmokeCutoffState.Unknown
        val targets = hooks.filter { it.name == SMOKE_CUTOFF_WEBHOOK_NAME }
            .firstOrNull()?.urls.orEmpty().mapNotNull(::parseSwitchSetUrl)
        return SmokeCutoffState.Configured(targets)
    }

    /** Décode `http://<ip>/rpc/Switch.Set?id=<n>&on=false` tel qu'écrit par [setSmokeCutoffTargets], ou `null` si autre chose. */
    private fun parseSwitchSetUrl(url: String): SmokeCutoffTarget? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (!uri.path.endsWith("/rpc/Switch.Set")) return null
        val id = (uri.query ?: return null).split("&")
            .mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 } }
            .firstOrNull { it[0] == "id" }
            ?.get(1)?.toIntOrNull() ?: return null
        return SmokeCutoffTarget(uri.host ?: return null, id)
    }

    /**
     * Remplace la configuration de coupure sur [detector] par exactement [targets] — repart de
     * zéro (supprime tout webhook `hestia_smoke_cutoff` existant, en recrée un par événement
     * câblé si [targets] n'est pas vide). Le webhook natif appelle directement `Switch.Set` sur
     * chaque prise visée, en GET (comme l'exemple officiel Shelly) — **autonome, sans app ni
     * script**, contrairement au relais ntfy du Lot 4 qui n'avait pas cette option.
     */
    suspend fun setSmokeCutoffTargets(detector: Device, targets: List<SmokeCutoffTarget>): RpcResult<Unit> {
        val (ip, listResult) = withIp(detector) { i -> rpcClient.webhookList(i) }
        listResult.errorOrNull()?.let { return it }
        for (hook in listResult.getOrNull()?.hooks.orEmpty().filter { it.name == SMOKE_CUTOFF_WEBHOOK_NAME }) {
            rpcClient.webhookDelete(ip, hook.id)
        }
        if (targets.isEmpty()) return RpcResult.Success(Unit)

        val urls = targets.map { "http://${it.ip}/rpc/Switch.Set?id=${it.switchId}&on=false" }
        for (event in SMOKE_CUTOFF_EVENTS) {
            rpcClient.webhookCreate(ip, cid = 0, event = event, name = SMOKE_CUTOFF_WEBHOOK_NAME, urls = urls)
                .errorOrNull()?.let { return it }
        }
        return RpcResult.Success(Unit)
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

        // --- Relais ntfy des détecteurs de fumée (Lot 4a, voir SMOKE-DETECTOR.md) ---
        val SMOKE_WEBHOOK_EVENTS = listOf("smoke.alarm", "smoke.alarm_off", "smoke.alarm_test")
        const val SMOKE_WEBHOOK_NAME = "hestia_smoke_relay"
        /** Limite dure du firmware Shelly (RPC `-108` au-delà), voir `ButtonTimerScriptGenerator`. */
        const val MAX_ENABLED_SCRIPTS_PER_DEVICE = 3
        /** Limite du firmware sur le nombre d'URLs d'un même webhook natif (`Webhook.Create`). */
        const val MAX_RELAY_TARGETS = 5

        // --- Coupure de prise en cas d'alarme (Lot 5, voir SMOKE-DETECTOR.md) ---
        const val SMOKE_CUTOFF_WEBHOOK_NAME = "hestia_smoke_cutoff"

        /**
         * Alarme réelle uniquement — `smoke.alarm_test` retiré le 2026-09-03 après validation en
         * conditions réelles (coupure confirmée dans la seconde suivant un appui long) : le garder
         * aurait coupé une vraie prise à chaque test mensuel de routine du détecteur.
         */
        val SMOKE_CUTOFF_EVENTS = listOf("smoke.alarm")
    }
}
