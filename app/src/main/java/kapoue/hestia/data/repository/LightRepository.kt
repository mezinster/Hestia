package kapoue.hestia.data.repository

import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.dao.DeviceDao
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
    private val logger: DiagnosticLogger,
) {
    // État en mémoire des variateurs démo : sans lui, le bouton et le curseur sembleraient cassés.
    private val demoStates = ConcurrentHashMap<Long, LightStatusResult>()

    suspend fun getStatus(device: Device): RpcResult<LightStatusResult> {
        if (device.ipAddress.startsWith(DeviceRepository.DEMO_IP_PREFIX)) {
            return RpcResult.Success(demoStates.getOrPut(device.id) { demoLightStatus(device) })
        }
        return deviceRepository.withIp(device) { ip -> lightRpc.getLightStatus(ip, device.switchId) }.second
    }

    suspend fun set(device: Device, on: Boolean?, brightness: Int?): RpcResult<LightSetResult> {
        if (device.ipAddress.startsWith(DeviceRepository.DEMO_IP_PREFIX)) {
            demoStates.compute(device.id) { _, current -> applyDemoLightSet(current ?: demoLightStatus(device), on, brightness) }
            return RpcResult.Success(LightSetResult())
        }
        return deviceRepository.withIp(device) { ip -> lightRpc.setLight(ip, device.switchId, on, brightness) }.second
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
            deviceDao.insert(
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
            added++
        }
        logger.info(DiagnosticLogger.DB, "Ajout variateur $ip : $added canal(aux) light sur ${capabilities.lightChannels.size}")
        return added
    }
}

/** État simulé d'un variateur démo — sans réseau, pour les captures et les tests manuels. */
internal fun demoLightStatus(device: Device): LightStatusResult =
    LightStatusResult(id = device.switchId, output = true, brightness = 40.0, apower = 6.2)

/** Applique une commande sur l'état démo : marche/arrêt et/ou luminosité, puissance recalculée. */
internal fun applyDemoLightSet(current: LightStatusResult, on: Boolean?, brightness: Int?): LightStatusResult {
    val output = on ?: current.output
    val level = brightness?.coerceIn(0, 100)?.toDouble() ?: current.brightness
    return current.copy(output = output, brightness = level, apower = if (output) (level ?: 100.0) * 0.155 else 0.0)
}
