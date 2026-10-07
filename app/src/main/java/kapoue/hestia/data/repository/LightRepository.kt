package kapoue.hestia.data.repository

import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.DeviceCapabilities
import kapoue.hestia.data.rpc.LightRpcClient
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.LightSetResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kapoue.hestia.domain.model.DeviceType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canaux variateur (`light:N`, 2026-10-07, fork) — chemin séparé des relais, comme les détecteurs
 * de fumée. Pas de repli cloud en v1 : injoignable en local = hors ligne.
 */
@Singleton
class LightRepository @Inject constructor(
    private val deviceDao: DeviceDao,
    private val lightRpc: LightRpcClient,
    private val deviceRepository: DeviceRepository,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
) {
    // État en mémoire des variateurs démo : sans lui, le bouton et le curseur sembleraient cassés.
    private val demoStates = ConcurrentHashMap<Long, LightStatusResult>()

    suspend fun getStatus(device: Device): RpcResult<LightStatusResult> {
        if (device.ipAddress.startsWith(DeviceRepository.DEMO_IP_PREFIX)) {
            val now = System.currentTimeMillis() / 1000.0
            return RpcResult.Success(
                demoStates.compute(device.id) { _, current -> expireDemoTimer(current ?: demoLightStatus(device, now), now) }!!,
            )
        }
        return deviceRepository.withIp(device) { ip -> lightRpc.getLightStatus(ip, device.switchId) }.second
    }

    /** [toggleAfterSec] : minuteur tenu par l'appareil (variateurs C1), voir [LightRpcClient.setLight]. */
    suspend fun set(device: Device, on: Boolean?, brightness: Int?, toggleAfterSec: Int? = null): RpcResult<LightSetResult> {
        if (device.ipAddress.startsWith(DeviceRepository.DEMO_IP_PREFIX)) {
            demoStates.compute(device.id) { _, current ->
                applyDemoLightSet(current ?: demoLightStatus(device), on, brightness, toggleAfterSec, System.currentTimeMillis() / 1000.0)
            }
            return RpcResult.Success(LightSetResult())
        }
        return deviceRepository.withIp(device) { ip -> lightRpc.setLight(ip, device.switchId, on, brightness, toggleAfterSec) }.second
    }

    /**
     * Ajoute tous les canaux light de l'appareil (pas d'écran de sélection en v1). Un canal dont
     * l'id est déjà pris sur cette IP (index unique ipAddress + switchId, ex. `switch:0` +
     * `light:0`) est ignoré et journalisé.
     */
    suspend fun addLightChannels(name: String, ip: String, capabilities: DeviceCapabilities): Int {
        var position = deviceDao.maxPosition() + 1
        val resolvedDeviceName = capabilities.reportedName?.takeIf { it.isNotBlank() } ?: name
        var added = 0
        for (lightId in capabilities.lightChannels) {
            if (deviceDao.exists(ip, lightId)) {
                logger.warn(DiagnosticLogger.DB, "Canal light $ip#$lightId ignoré : id déjà pris sur cette IP")
                continue
            }
            val channelName = capabilities.lightChannelNames[lightId]
                ?: if (capabilities.lightChannels.size > 1) "$name · ${lightId + 1}" else name
            val newId = deviceDao.insert(
                Device(
                    name = channelName,
                    deviceName = resolvedDeviceName,
                    ipAddress = ip,
                    switchId = lightId,
                    type = DeviceType.LAMP,
                    model = capabilities.model,
                    supportsSwitch = false,
                    isLight = true,
                    hasScripting = capabilities.hasScripting,
                    hasPowerMetering = capabilities.hasPowerMetering,
                    position = position++,
                ),
            )
            // Nom inventé ici (absent de l'appareil) : à pousser dessus. Le Tableau le fera au
            // prochain relevé ([DeviceRepository.nameCatchUpIfNeeded]), comme pour un relais injoignable.
            if (capabilities.lightChannelNames[lightId] == null || capabilities.reportedName.isNullOrBlank()) {
                appPreferences.markNameUnsynced(newId)
            }
            added++
        }
        logger.info(DiagnosticLogger.DB, "Ajout variateur $ip : $added canal(aux) light sur ${capabilities.lightChannels.size}")
        return added
    }
}

/**
 * État simulé d'un variateur démo — sans réseau, pour les captures et les tests manuels. Le bloc
 * multi-light « Kitchen Lights » (.11) varie par canal pour montrer d'un coup d'œil la ligne de
 * cercles : allumé, éteint, minuteur en cours (démarré à la première lecture), pleine intensité.
 */
internal fun demoLightStatus(device: Device, nowEpochSec: Double = 0.0): LightStatusResult {
    if (device.ipAddress != "${DeviceRepository.DEMO_IP_PREFIX}11") {
        return LightStatusResult(id = device.switchId, output = true, brightness = 40.0, apower = 6.2)
    }
    return when (device.switchId) {
        0 -> LightStatusResult(id = 0, output = true, brightness = 80.0, apower = 12.4)
        1 -> LightStatusResult(id = 1, output = false, brightness = 60.0, apower = 0.0)
        2 -> LightStatusResult(
            id = 2, output = true, brightness = 25.0, apower = 3.9,
            timerStartedAt = nowEpochSec, timerDuration = 2700.0,
        )
        else -> LightStatusResult(id = device.switchId, output = true, brightness = 100.0, apower = 15.5)
    }
}

/**
 * Applique une commande sur l'état démo : marche/arrêt et/ou luminosité, puissance recalculée. Un
 * minuteur ([toggleAfterSec]) est simulé comme sur l'appareil ; éteindre l'annule.
 */
internal fun applyDemoLightSet(
    current: LightStatusResult,
    on: Boolean?,
    brightness: Int?,
    toggleAfterSec: Int? = null,
    nowEpochSec: Double = 0.0,
): LightStatusResult {
    val output = on ?: current.output
    val level = brightness?.coerceIn(0, 100)?.toDouble() ?: current.brightness
    val timed = output && toggleAfterSec != null
    return current.copy(
        output = output,
        brightness = level,
        apower = if (output) (level ?: 100.0) * 0.155 else 0.0,
        timerStartedAt = if (timed) nowEpochSec else current.timerStartedAt.takeIf { output },
        timerDuration = if (timed) toggleAfterSec!!.toDouble() else current.timerDuration.takeIf { output },
    )
}

/** Minuteur démo échu : la lampe s'éteint seule, comme le ferait l'appareil (`toggle_after`). */
internal fun expireDemoTimer(current: LightStatusResult, nowEpochSec: Double): LightStatusResult {
    val startedAt = current.timerStartedAt ?: return current
    val duration = current.timerDuration ?: return current
    if (startedAt + duration > nowEpochSec) return current
    return current.copy(output = false, apower = 0.0, timerStartedAt = null, timerDuration = null)
}
