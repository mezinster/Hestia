package kapoue.hestia.ui.screens.presence

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.R
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.ui.common.UserMessage
import kapoue.hestia.ui.common.toUserMessageOrNull
import kapoue.hestia.ui.navigation.StackedRoutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import javax.inject.Inject

data class PresenceUiState(
    val deviceName: String = "",
    val startHour: Int = 19,
    val startMinute: Int = 0,
    val endHour: Int = 23,
    val endMinute: Int = 0,
    val marginMinutes: Int = 20,
    /** Simulation réellement en cours sur l'appareil. */
    val deployed: Boolean = false,
    val isBusy: Boolean = false,
    val error: UserMessage? = null,
    /** Écart d'horloge appareil/téléphone en secondes (null = inconnu). */
    val clockDriftSeconds: Long? = null,
    val deviceTimeLabel: String? = null,
    val loaded: Boolean = false,
) {
    val clockDrifted: Boolean get() = (clockDriftSeconds ?: 0) > DRIFT_THRESHOLD_SECONDS

    companion object {
        const val DRIFT_THRESHOLD_SECONDS = 120L
    }
}

@HiltViewModel
class PresenceViewModel @Inject constructor(
    private val repository: DeviceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val deviceId: Long = checkNotNull(savedStateHandle.get<Long>(StackedRoutes.PRESENCE_ARG_ID))

    private val _uiState = MutableStateFlow(PresenceUiState())
    val uiState: StateFlow<PresenceUiState> = _uiState.asStateFlow()

    @Volatile
    private var permissionUsable: Boolean = true

    init {
        viewModelScope.launch {
            val device = repository.getDevice(deviceId)
            val config = repository.getPresenceConfig(deviceId)
            _uiState.update {
                it.copy(
                    deviceName = device?.name.orEmpty(),
                    startHour = config?.startHour ?: it.startHour,
                    startMinute = config?.startMinute ?: it.startMinute,
                    endHour = config?.endHour ?: it.endHour,
                    endMinute = config?.endMinute ?: it.endMinute,
                    marginMinutes = config?.randomMarginMinutes ?: it.marginMinutes,
                    loaded = true,
                )
            }
            refreshState()
        }
    }

    fun updatePermission(usable: Boolean) {
        permissionUsable = usable
    }

    /** Relit l'état réel (script actif ?) et l'horloge de l'appareil. */
    fun refreshState() {
        if (!permissionUsable) return
        viewModelScope.launch {
            val device = repository.getDevice(deviceId) ?: return@launch
            repository.getPresenceState(device).getOrNull()?.let { state ->
                _uiState.update { it.copy(deployed = state.running) }
            }
            repository.getDeviceClock(device).getOrNull()?.let { clock ->
                val drift = abs(clock.unixtime - System.currentTimeMillis() / 1000)
                _uiState.update { it.copy(clockDriftSeconds = drift, deviceTimeLabel = clock.timeLabel) }
            }
        }
    }

    fun onStartTime(hour: Int, minute: Int) = _uiState.update {
        it.copy(startHour = hour, startMinute = minute, error = null)
    }

    fun onEndTime(hour: Int, minute: Int) = _uiState.update {
        it.copy(endHour = hour, endMinute = minute, error = null)
    }

    fun onMarginChange(minutes: Int) = _uiState.update {
        it.copy(marginMinutes = minutes, error = null)
    }

    fun deploy() {
        val s = _uiState.value
        val startTotal = s.startHour * 60 + s.startMinute
        val endTotal = s.endHour * 60 + s.endMinute
        when {
            // Début == fin interdit ; fin < début = plage de nuit (22h -> 6h), autorisée.
            endTotal == startTotal ->
                return _uiState.update { it.copy(error = UserMessage(R.string.presence_error_end_before_start)) }
            s.marginMinutes !in 0..120 ->
                return _uiState.update { it.copy(error = UserMessage(R.string.presence_error_margin)) }
        }
        _uiState.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val device = repository.getDevice(deviceId) ?: return@launch
            // Présence et planning s'excluent : refuser si un planning existe déjà sur la prise.
            if (repository.getPlannings(device).getOrNull().orEmpty().isNotEmpty()) {
                _uiState.update { it.copy(isBusy = false, error = UserMessage(R.string.presence_error_planning_exists)) }
                return@launch
            }
            val result = repository.deployPresence(device, s.startHour, s.startMinute, s.endHour, s.endMinute, s.marginMinutes)
            if (result is RpcResult.Success) {
                _uiState.update { it.copy(isBusy = false, deployed = true) }
                refreshState()
            } else {
                _uiState.update { it.copy(isBusy = false, error = result.toUserMessageOrNull()) }
            }
        }
    }

    fun stop() {
        _uiState.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val device = repository.getDevice(deviceId) ?: return@launch
            val result = repository.stopPresence(device)
            if (result is RpcResult.Success) {
                _uiState.update { it.copy(isBusy = false, deployed = false) }
                refreshState()
            } else {
                _uiState.update { it.copy(isBusy = false, error = result.toUserMessageOrNull()) }
            }
        }
    }
}
