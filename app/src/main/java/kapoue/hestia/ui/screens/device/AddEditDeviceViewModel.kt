package kapoue.hestia.ui.screens.device

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.R
import kapoue.hestia.core.util.isValidIpv4
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.DeviceCapabilities
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.domain.model.CloudInfo
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.DriverType
import kapoue.hestia.domain.model.FirmwareCheckResult
import kapoue.hestia.domain.model.LedNightModeState
import kapoue.hestia.ui.common.UserMessage
import kapoue.hestia.ui.common.toUserMessageOrNull
import kapoue.hestia.ui.navigation.StackedRoutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Sélection des canaux détectés sur un appareil multi-canaux. */
data class ChannelSelection(
    val model: String?,
    val channels: List<Int>,
    val selected: Set<Int>,
)

data class AddEditUiState(
    val isEditMode: Boolean = false,
    /** Vrai si l'appareil édité a plusieurs canaux : le champ [name] représente alors le nom
     * de l'appareil physique (partagé), pas le nom d'un canal individuel. */
    val isGroupEdit: Boolean = false,
    val name: String = "",
    val ipAddress: String = "",
    /** Nom du 1ᵉʳ emplacement IP. Null = nom par défaut affiché (traduit, jamais stocké tel quel). */
    val ipName: String? = null,
    /** Deuxième adresse IP optionnelle. Null = un seul emplacement configuré. */
    val ip2Address: String? = null,
    val ip2Name: String? = null,
    val type: DeviceType = DeviceType.PLUG,
    val isTesting: Boolean = false,
    val nameError: UserMessage? = null,
    val ipError: UserMessage? = null,
    /** Erreur globale (réseau, RPC, doublon). */
    val error: UserMessage? = null,
    /** Non nul lorsque plusieurs canaux ont été détectés et attendent une sélection. */
    val channelSelection: ChannelSelection? = null,
    /** True quand l'opération est terminée : l'écran peut se refermer. */
    val done: Boolean = false,
    val isDirty: Boolean = false,
    /** LED d'état de l'appareil. Null = lecture en cours (mode édition seulement). */
    val ledState: LedNightModeState? = null,
    /** Pilote de l'appareil édité (Shelly Gen2+ pour l'instant) — conditionne le redémarrage,
     * propre au fabricant. Valeur par défaut sans portée hors mode édition. */
    val driver: DriverType = DriverType.SHELLY_GEN2,
    /** Résultat de la dernière vérification manuelle de mise à jour firmware. */
    val firmwareCheck: FirmwareCheckResult? = null,
    val firmwareChecking: Boolean = false,
    val firmwareInstalling: Boolean = false,
    /** Message transitoire de l'installation (démarrée ou en échec), consommé par l'UI. */
    val firmwareInstallMessage: UserMessage? = null,
    val rebooting: Boolean = false,
    /** Message transitoire du redémarrage (lancé ou en échec), consommé par l'UI. */
    val rebootMessage: UserMessage? = null,
    /** État Cloud Shelly. Null = lecture en cours (mode édition seulement). */
    val cloudInfo: CloudInfo? = null,
    val cloudToggling: Boolean = false,
)

@HiltViewModel
class AddEditDeviceViewModel @Inject constructor(
    private val repository: DeviceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editingDeviceId: Long? =
        savedStateHandle.get<Long>(StackedRoutes.EDIT_DEVICE_ARG_ID)

    private val _uiState = MutableStateFlow(AddEditUiState(isEditMode = editingDeviceId != null))
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    // Instantané initial pour détecter les modifications non enregistrées.
    private var initialName = ""
    private var initialIp = ""
    private var initialType = DeviceType.PLUG
    private var initialIpName: String? = null
    private var initialIp2Address: String? = null
    private var initialIp2Name: String? = null

    // Capacités détectées par la dernière sonde réussie, persistées à l'ajout.
    private var capabilities: DeviceCapabilities? = null

    // Tous les canaux du même appareil physique (même IP), y compris celui édité — un seul élément
    // hors mode groupé. Utilisé pour appliquer nom/IP/type à l'ensemble à l'enregistrement.
    private var groupMembers: List<Device> = emptyList()

    // Nom du composant LED détecté par le dernier getLedState() réussi — nécessaire pour écrire
    // avec setLedState() sans re-détecter (nom et casse varient selon le modèle, voir DeviceRepository).
    private var ledComponent: String? = null

    init {
        editingDeviceId?.let { id ->
            viewModelScope.launch {
                repository.getDevice(id)?.let { device ->
                    val siblings = repository.getDevicesOnce().filter { it.ipAddress == device.ipAddress && it.id != device.id }
                    groupMembers = listOf(device) + siblings
                    val isGroupEdit = siblings.isNotEmpty()
                    // Mode groupé : le champ Nom porte le nom de l'appareil physique (partagé),
                    // jamais le nom d'un canal individuel — celui-ci se modifie ailleurs (Réglages).
                    val editedName = if (isGroupEdit) device.deviceName else device.name
                    initialName = editedName
                    initialIp = device.ipAddress
                    initialType = device.type
                    initialIpName = device.ipName
                    initialIp2Address = device.ip2Address
                    initialIp2Name = device.ip2Name
                    _uiState.update {
                        it.copy(
                            isGroupEdit = isGroupEdit,
                            name = editedName, ipAddress = device.ipAddress, type = device.type,
                            ipName = device.ipName, ip2Address = device.ip2Address, ip2Name = device.ip2Name,
                            driver = device.driver,
                        )
                    }
                    val (component, ledState) = repository.getLedState(groupMembers)
                    ledComponent = component
                    _uiState.update { it.copy(ledState = ledState) }

                    val cloudInfo = repository.getCloudInfo(device)
                    _uiState.update { it.copy(cloudInfo = cloudInfo) }
                    (cloudInfo as? CloudInfo.Available)?.macId?.let { repository.cacheCloudId(groupMembers, it) }
                }
            }
        }
    }

    /** Bascule la LED — relit l'état réel ensuite plutôt que de le supposer, en cas d'échec compris. */
    fun onToggleLed(enabled: Boolean) {
        val component = ledComponent ?: return
        viewModelScope.launch {
            repository.setLedState(groupMembers, component, enabled)
            val (refreshedComponent, ledState) = repository.getLedState(groupMembers)
            ledComponent = refreshedComponent
            _uiState.update { it.copy(ledState = ledState) }
        }
    }

    // --- Mise à jour et redémarrage firmware — par appareil physique (2026-08-19), un seul
    // canal du groupe suffit (même firmware, même redémarrage pour tous). Anciennement dupliqué
    // à l'identique sur l'écran Détail de chaque canal.

    /** Vérification manuelle (seul appel du projet qui sort du réseau local). */
    fun checkFirmwareUpdate() {
        val dev = groupMembers.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(firmwareChecking = true, firmwareInstallMessage = null) }
            val result = repository.checkFirmwareUpdate(dev)
            _uiState.update { it.copy(firmwareChecking = false, firmwareCheck = result) }
        }
    }

    /** Démarre l'installation de la mise à jour stable ; l'appareil redémarre pour l'appliquer. */
    fun installFirmwareUpdate() {
        val dev = groupMembers.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(firmwareInstalling = true) }
            val result = repository.installFirmwareUpdate(dev)
            _uiState.update {
                it.copy(
                    firmwareInstalling = false,
                    // Redémarrage à venir : l'état vérifié devient obsolète.
                    firmwareCheck = if (result is RpcResult.Success) null else it.firmwareCheck,
                    firmwareInstallMessage = if (result is RpcResult.Success) {
                        UserMessage(R.string.firmware_install_started)
                    } else {
                        UserMessage(R.string.firmware_install_error)
                    },
                )
            }
        }
    }

    fun consumeFirmwareInstallMessage() {
        _uiState.update { it.copy(firmwareInstallMessage = null) }
    }

    /** Redémarre l'appareil (dépannage) ; jamais bloqué par un minuteur en cours. */
    fun rebootDevice() {
        val dev = groupMembers.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(rebooting = true) }
            val result = repository.rebootDevice(dev)
            _uiState.update {
                it.copy(
                    rebooting = false,
                    firmwareCheck = if (result is RpcResult.Success) null else it.firmwareCheck,
                    rebootMessage = if (result is RpcResult.Success) {
                        UserMessage(R.string.firmware_reboot_started)
                    } else {
                        UserMessage(R.string.firmware_reboot_error)
                    },
                )
            }
        }
    }

    fun consumeRebootMessage() {
        _uiState.update { it.copy(rebootMessage = null) }
    }

    // --- Cloud Shelly — par appareil physique, comme la LED et le firmware ci-dessus. Toujours
    // à la demande explicite de l'utilisateur, jamais activé par Hestia de sa propre initiative.

    fun onToggleCloud(enabled: Boolean) {
        val dev = groupMembers.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(cloudToggling = true) }
            repository.setCloudEnabled(dev, enabled)
            val cloudInfo = repository.getCloudInfo(dev)
            _uiState.update { it.copy(cloudToggling = false, cloudInfo = cloudInfo) }
            (cloudInfo as? CloudInfo.Available)?.macId?.let { repository.cacheCloudId(groupMembers, it) }
        }
    }

    fun onNameChange(value: String) = _uiState.update {
        val next = it.copy(name = value, nameError = null, error = null)
        next.copy(isDirty = isDirty(next))
    }

    fun onIpChange(value: String) = _uiState.update {
        val next = it.copy(ipAddress = value, ipError = null, error = null)
        next.copy(isDirty = isDirty(next))
    }

    fun onTypeChange(value: DeviceType) = _uiState.update {
        val next = it.copy(type = value)
        next.copy(isDirty = isDirty(next))
    }

    /** Modifie (ou nomme pour la première fois) le 1ᵉʳ emplacement IP. Toujours présent. */
    fun onEditIpSlot1(name: String, ip: String) = _uiState.update {
        val next = it.copy(ipAddress = ip, ipName = name.ifBlank { null }, ipError = null, error = null)
        next.copy(isDirty = isDirty(next))
    }

    /** Ajoute ou modifie le 2ᵉ emplacement IP optionnel. */
    fun onEditIpSlot2(name: String, ip: String) = _uiState.update {
        val next = it.copy(ip2Address = ip, ip2Name = name.ifBlank { null })
        next.copy(isDirty = isDirty(next))
    }

    /** Supprime le 2ᵉ emplacement IP. Le 1ᵉʳ reste toujours présent (appareil jamais sans IP). */
    fun onDeleteIpSlot2() = _uiState.update {
        val next = it.copy(ip2Address = null, ip2Name = null)
        next.copy(isDirty = isDirty(next))
    }

    private fun isDirty(state: AddEditUiState): Boolean =
        state.name != initialName || state.ipAddress != initialIp || state.type != initialType ||
            state.ipName != initialIpName || state.ip2Address != initialIp2Address || state.ip2Name != initialIp2Name

    /** Valide les champs (public pour gater la demande de permission avant tout contact réseau). */
    fun validate(): Boolean = validateFields()

    /** Valide les champs. Renvoie true si valides (et pose les erreurs sinon). */
    private fun validateFields(): Boolean {
        val state = _uiState.value
        val nameError = if (state.name.isBlank()) UserMessage(R.string.error_name_required) else null
        val ipError = when {
            state.ipAddress.isBlank() -> UserMessage(R.string.error_ip_required)
            !isValidIpv4(state.ipAddress) -> UserMessage(R.string.error_ip_invalid)
            else -> null
        }
        _uiState.update { it.copy(nameError = nameError, ipError = ipError) }
        return nameError == null && ipError == null
    }

    /**
     * Mode ajout : teste la connexion (Shelly.GetDeviceInfo + GetComponents), rejette les Gen1,
     * puis ajoute directement si un seul canal, ou propose la sélection si plusieurs.
     * L'appelant doit s'être assuré que la permission réseau local est utilisable.
     */
    fun testAndAdd() {
        if (!validateFields()) return
        val state = _uiState.value
        _uiState.update { it.copy(isTesting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.probe(state.ipAddress.trim())) {
                is RpcResult.Success -> {
                    val caps = result.value
                    capabilities = caps
                    // Repli : si aucun canal switch n'est remonté, supposer le canal 0.
                    val channels = caps.switchChannels.ifEmpty { listOf(0) }
                    if (channels.size == 1) {
                        addChannels(channels)
                    } else {
                        _uiState.update {
                            it.copy(
                                isTesting = false,
                                channelSelection = ChannelSelection(
                                    model = caps.model,
                                    channels = channels,
                                    selected = channels.toSet(),
                                ),
                            )
                        }
                    }
                }
                else -> _uiState.update {
                    it.copy(isTesting = false, error = result.toUserMessageOrNull())
                }
            }
        }
    }

    /**
     * Mode ajout d'un détecteur de fumée : pas de sonde, contrairement à [testAndAdd] — voir
     * SMOKE-DETECTOR.md. L'IP saisie est prise telle quelle, jamais vérifiée à ce stade.
     */
    fun addSmokeDetector() {
        if (!validateFields()) return
        val state = _uiState.value
        viewModelScope.launch {
            val added = repository.addSmokeDetector(state.name.trim(), state.ipAddress.trim())
            if (added) {
                // Un appareil scriptable a peut-être de la place pour relayer ses alertes ntfy
                // dès maintenant (voir SMOKE-DETECTOR.md § Lot 4a) — best-effort, silencieux.
                repository.resyncSmokeRelay()
                _uiState.update { it.copy(done = true) }
            } else {
                _uiState.update { it.copy(error = UserMessage(R.string.error_device_exists)) }
            }
        }
    }

    fun toggleChannel(channelId: Int) = _uiState.update { state ->
        val selection = state.channelSelection ?: return@update state
        val newSelected = if (channelId in selection.selected) {
            selection.selected - channelId
        } else {
            selection.selected + channelId
        }
        state.copy(channelSelection = selection.copy(selected = newSelected))
    }

    fun confirmChannelSelection() {
        val state = _uiState.value
        val selection = state.channelSelection ?: return
        if (selection.selected.isEmpty()) {
            _uiState.update { it.copy(error = UserMessage(R.string.error_channel_required)) }
            return
        }
        _uiState.update { it.copy(isTesting = true, error = null) }
        viewModelScope.launch {
            addChannels(selection.selected.sorted())
        }
    }

    private suspend fun addChannels(channels: List<Int>) {
        val caps = capabilities ?: return
        val state = _uiState.value
        val added = repository.addChannels(
            name = state.name.trim(),
            ip = state.ipAddress.trim(),
            type = state.type,
            capabilities = caps,
            switchIds = channels,
        )
        if (added == 0) {
            _uiState.update {
                it.copy(isTesting = false, channelSelection = null, error = UserMessage(R.string.error_device_exists))
            }
        } else {
            _uiState.update { it.copy(isTesting = false, done = true) }
        }
    }

    /** Mode modification : enregistre les champs (nom, IP, type) sans réinterroger l'appareil. */
    fun saveEdit() {
        if (!validateFields()) return
        val id = editingDeviceId ?: return
        viewModelScope.launch {
            val existing = repository.getDevice(id) ?: return@launch
            val state = _uiState.value
            if (state.isGroupEdit) {
                // Champs partagés (nom d'appareil, IP, type) appliqués à tous les canaux d'un
                // coup — jamais le nom de canal individuel, laissé tel quel sur chacun.
                val outcome = runCatching {
                    repository.updateDeviceGroup(
                        members = groupMembers,
                        deviceName = state.name.trim(),
                        ipAddress = state.ipAddress.trim(),
                        ip2Address = state.ip2Address,
                        ipName = state.ipName,
                        ip2Name = state.ip2Name,
                        type = state.type,
                    )
                }
                if (outcome.isFailure) {
                    _uiState.update { it.copy(error = UserMessage(R.string.error_device_exists)) }
                } else {
                    _uiState.update { it.copy(done = true) }
                }
                return@launch
            }
            val nameChanged = state.name.trim() != existing.name
            val updated = existing.copy(
                name = state.name.trim(),
                deviceName = state.name.trim(),
                ipAddress = state.ipAddress.trim(),
                type = state.type,
                ipName = state.ipName,
                ip2Address = state.ip2Address,
                ip2Name = state.ip2Name,
            )
            val outcome = runCatching { repository.updateDevice(updated) }
            if (outcome.isFailure) {
                // Violation de la contrainte d'unicité (ipAddress, switchId).
                _uiState.update { it.copy(error = UserMessage(R.string.error_device_exists)) }
            } else {
                // Le nom est écrit en dur dans les scripts déployés (notifs ntfy) : les redéployer
                // avec le nouveau nom si besoin. Lancé par le dépôt sur sa propre portée (pas
                // viewModelScope) : l'écran se ferme aussitôt après (voir onDone ci-dessous), ce
                // qui tuerait la resynchro avant la fin si elle dépendait de ce ViewModel.
                if (nameChanged) repository.resyncDeviceName(updated)
                _uiState.update { it.copy(done = true) }
            }
        }
    }
}
