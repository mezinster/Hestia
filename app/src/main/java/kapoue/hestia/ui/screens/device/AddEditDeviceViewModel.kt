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
import kapoue.hestia.domain.model.DeviceType
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
    val name: String = "",
    val ipAddress: String = "",
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

    // Capacités détectées par la dernière sonde réussie, persistées à l'ajout.
    private var capabilities: DeviceCapabilities? = null

    init {
        editingDeviceId?.let { id ->
            viewModelScope.launch {
                repository.getDevice(id)?.let { device ->
                    initialName = device.name
                    initialIp = device.ipAddress
                    initialType = device.type
                    _uiState.update {
                        it.copy(name = device.name, ipAddress = device.ipAddress, type = device.type)
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) = _uiState.update {
        it.copy(name = value, nameError = null, error = null, isDirty = isDirty(value, it.ipAddress, it.type))
    }

    fun onIpChange(value: String) = _uiState.update {
        it.copy(ipAddress = value, ipError = null, error = null, isDirty = isDirty(it.name, value, it.type))
    }

    fun onTypeChange(value: DeviceType) = _uiState.update {
        it.copy(type = value, isDirty = isDirty(it.name, it.ipAddress, value))
    }

    private fun isDirty(name: String, ip: String, type: DeviceType): Boolean =
        name != initialName || ip != initialIp || type != initialType

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
            val updated = existing.copy(
                name = _uiState.value.name.trim(),
                ipAddress = _uiState.value.ipAddress.trim(),
                type = _uiState.value.type,
            )
            val outcome = runCatching { repository.updateDevice(updated) }
            if (outcome.isFailure) {
                // Violation de la contrainte d'unicité (ipAddress, switchId).
                _uiState.update { it.copy(error = UserMessage(R.string.error_device_exists)) }
            } else {
                _uiState.update { it.copy(done = true) }
            }
        }
    }
}
