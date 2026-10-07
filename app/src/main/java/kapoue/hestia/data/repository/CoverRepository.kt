package kapoue.hestia.data.repository

import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.dao.DeviceDao
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.rpc.CoverRpcClient
import kapoue.hestia.data.rpc.DeviceCapabilities
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.CoverStatusResult
import kapoue.hestia.data.rpc.model.SetConfigResult
import kapoue.hestia.domain.model.DeviceType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Durée simulée d'une calibration démo, en ms. */
private const val DEMO_CALIBRATION_MS = 5_000L

/**
 * Canaux volet (`cover:N`, lot S1, 2026-10-07, fork) — chemin séparé des relais et des
 * variateurs. Pas de repli cloud : injoignable en local = hors ligne.
 */
@Singleton
class CoverRepository @Inject constructor(
    private val deviceDao: DeviceDao,
    private val coverRpc: CoverRpcClient,
    private val deviceRepository: DeviceRepository,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
) {
    /** État démo en mémoire : état de base, mouvement en cours, fin de calibration simulée. */
    private data class DemoCover(
        val base: CoverStatusResult,
        val move: DemoCoverMove? = null,
        val calibrationEndsAtMs: Long? = null,
    )

    private val demoStates = ConcurrentHashMap<Long, DemoCover>()

    private fun isDemo(device: Device) = device.ipAddress.startsWith(DeviceRepository.DEMO_IP_PREFIX)

    /** Applique la fin de calibration échue, puis calcule l'état simulé à [nowMs]. */
    private fun settle(current: DemoCover, nowMs: Long): DemoCover {
        val ends = current.calibrationEndsAtMs
        if (ends != null && nowMs >= ends) {
            return DemoCover(current.base.copy(state = "open", currentPos = 100, posControl = true, apower = 0.0))
        }
        return current
    }

    private fun demoStatus(device: Device, nowMs: Long): CoverStatusResult {
        val settled = demoStates.compute(device.id) { _, cur -> settle(cur ?: DemoCover(demoCoverStatus(device)), nowMs) }!!
        return if (settled.calibrationEndsAtMs != null) {
            settled.base.copy(state = "calibrating", apower = 35.0)
        } else {
            demoCoverAt(settled.base, settled.move, nowMs)
        }
    }

    suspend fun getStatus(device: Device): RpcResult<CoverStatusResult> {
        if (isDemo(device)) return RpcResult.Success(demoStatus(device, System.currentTimeMillis()))
        return deviceRepository.withIp(device) { ip -> coverRpc.getStatus(ip, device.switchId) }.second
    }

    suspend fun open(device: Device): RpcResult<SetConfigResult> =
        demoMoveOrRpc(device, target = 100) { ip -> coverRpc.open(ip, device.switchId) }

    suspend fun close(device: Device): RpcResult<SetConfigResult> =
        demoMoveOrRpc(device, target = 0) { ip -> coverRpc.close(ip, device.switchId) }

    suspend fun goTo(device: Device, pos: Int): RpcResult<SetConfigResult> =
        demoMoveOrRpc(device, target = pos.coerceIn(0, 100)) { ip -> coverRpc.goToPosition(ip, device.switchId, pos) }

    suspend fun stop(device: Device): RpcResult<SetConfigResult> {
        if (isDemo(device)) {
            val now = System.currentTimeMillis()
            // Fige la position courante : le mouvement est remplacé par l'état simulé « stopped ».
            demoStates.compute(device.id) { _, cur ->
                val settled = settle(cur ?: DemoCover(demoCoverStatus(device)), now)
                DemoCover(demoCoverAt(settled.base, settled.move, now).copy(state = "stopped", apower = 0.0))
            }
            return RpcResult.Success(SetConfigResult())
        }
        return deviceRepository.withIp(device) { ip -> coverRpc.stop(ip, device.switchId) }.second
    }

    suspend fun calibrate(device: Device): RpcResult<SetConfigResult> {
        if (isDemo(device)) {
            val now = System.currentTimeMillis()
            demoStates.compute(device.id) { _, cur ->
                DemoCover((cur ?: DemoCover(demoCoverStatus(device))).base, null, now + DEMO_CALIBRATION_MS)
            }
            return RpcResult.Success(SetConfigResult())
        }
        return deviceRepository.withIp(device) { ip -> coverRpc.calibrate(ip, device.switchId) }.second
    }

    private suspend fun demoMoveOrRpc(
        device: Device,
        target: Int,
        call: suspend (ip: String) -> RpcResult<SetConfigResult>,
    ): RpcResult<SetConfigResult> {
        if (!isDemo(device)) return deviceRepository.withIp(device, call).second
        val now = System.currentTimeMillis()
        demoStates.compute(device.id) { _, cur ->
            val settled = settle(cur ?: DemoCover(demoCoverStatus(device)), now)
            val from = demoCoverAt(settled.base, settled.move, now).currentPos ?: 0
            // Le nouveau mouvement part de la position simulée courante, sans saut.
            val base = demoCoverAt(settled.base, settled.move, now).copy(state = "stopped", apower = 0.0)
            DemoCover(base, DemoCoverMove(from = from, target = target, startedAtMs = now))
        }
        return RpcResult.Success(SetConfigResult())
    }

    /**
     * Ajoute tous les canaux cover de l'appareil (pas d'écran de sélection en v1). Un canal dont
     * l'id est déjà pris sur cette IP (index unique ipAddress + switchId) est ignoré et journalisé.
     */
    suspend fun addCoverChannels(name: String, ip: String, capabilities: DeviceCapabilities): Int {
        var position = deviceDao.maxPosition() + 1
        val resolvedDeviceName = capabilities.reportedName?.takeIf { it.isNotBlank() } ?: name
        var added = 0
        for (coverId in capabilities.coverChannels) {
            if (deviceDao.exists(ip, coverId)) {
                logger.warn(DiagnosticLogger.DB, "Canal cover $ip#$coverId ignoré : id déjà pris sur cette IP")
                continue
            }
            val channelName = capabilities.coverChannelNames[coverId]
                ?: if (capabilities.coverChannels.size > 1) "$name · ${coverId + 1}" else name
            val newId = deviceDao.insert(
                buildCoverDevice(channelName, resolvedDeviceName, ip, coverId, capabilities, position++),
            )
            // Nom inventé ici (absent de l'appareil) : à pousser dessus au prochain relevé.
            if (capabilities.coverChannelNames[coverId] == null || capabilities.reportedName.isNullOrBlank()) {
                appPreferences.markNameUnsynced(newId)
            }
            added++
        }
        logger.info(DiagnosticLogger.DB, "Ajout volet $ip : $added canal(aux) cover sur ${capabilities.coverChannels.size}")
        return added
    }
}

/** Entité d'un canal volet : ni relais ni variateur. */
internal fun buildCoverDevice(
    name: String,
    deviceName: String,
    ip: String,
    coverId: Int,
    capabilities: DeviceCapabilities,
    position: Int,
): Device = Device(
    name = name,
    deviceName = deviceName,
    ipAddress = ip,
    switchId = coverId,
    type = DeviceType.SHUTTER,
    model = capabilities.model,
    supportsSwitch = false,
    isCover = true,
    hasScripting = capabilities.hasScripting,
    hasPowerMetering = capabilities.hasPowerMetering,
    position = position,
)
