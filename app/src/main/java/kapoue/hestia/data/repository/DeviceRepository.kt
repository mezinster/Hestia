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
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.ShellyRpcClient
import kapoue.hestia.data.rpc.errorOrNull
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.data.rpc.model.SwitchSetResult
import kapoue.hestia.data.rpc.model.SwitchStatusResult
import kapoue.hestia.domain.model.ActivationAction
import kapoue.hestia.domain.model.DeviceType
import kotlinx.coroutines.flow.Flow
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
        val result = rpcClient.setSwitch(device.ipAddress, device.switchId, on)
        if (result is RpcResult.Success) {
            // Persister l'état commandé : évite de re-journaliser ce changement au prochain relevé.
            deviceDao.updateLastKnownOutput(device.id, on)
            logActivation(device.id, if (on) ActivationAction.TURNED_ON else ActivationAction.TURNED_OFF)
        }
        return result
    }

    /**
     * Démarre le minuteur natif auto_off puis allume le canal. Autonome ensuite : l'appareil
     * gère le compte à rebours, le téléphone peut être fermé. [detail] est journalisé (durée).
     */
    suspend fun startTimer(device: Device, seconds: Int, detail: String?): RpcResult<Unit> =
        when (val cfg = rpcClient.setSwitchConfig(device.ipAddress, device.switchId, autoOff = true, autoOffDelaySec = seconds)) {
            is RpcResult.Success -> when (val set = rpcClient.setSwitch(device.ipAddress, device.switchId, on = true)) {
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
            is RpcResult.RpcError -> cfg
            is RpcResult.Failure -> cfg
        }

    /**
     * Annule le minuteur : retire l'auto_off **puis éteint le canal**. Annuler = ne plus laisser
     * passer le courant (et éviter que l'auto_off se ré-arme au prochain allumage).
     */
    suspend fun cancelTimer(device: Device): RpcResult<Unit> {
        when (val cfg = rpcClient.setSwitchConfig(device.ipAddress, device.switchId, autoOff = false)) {
            is RpcResult.Success -> Unit
            is RpcResult.RpcError -> return cfg
            is RpcResult.Failure -> return cfg
        }
        return when (val set = rpcClient.setSwitch(device.ipAddress, device.switchId, on = false)) {
            is RpcResult.Success -> {
                deviceDao.updateLastKnownOutput(device.id, false)
                logActivation(device.id, ActivationAction.TIMER_CANCELLED)
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

    /**
     * Génère, pousse et démarre le script de présence. Décision A : coupe d'abord l'`auto_off`
     * pour que le script soit seul maître du relais. Réutilise un script `hestia_presence`
     * existant, ne touche jamais un script d'un autre nom.
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
        // 1. Couper l'auto_off éventuel (conflit minuteur).
        rpcClient.setSwitchConfig(ip, device.switchId, autoOff = false).errorOrNull()?.let { return it }

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
    }
}
