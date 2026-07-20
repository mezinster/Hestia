package kapoue.hestia.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.data.local.entity.ActivationLog
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.ui.navigation.StackedRoutes
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kapoue.hestia.ui.screens.dashboard.toTileStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: DeviceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val deviceId: Long = checkNotNull(savedStateHandle.get<Long>(StackedRoutes.DETAIL_ARG_ID))

    val device: StateFlow<Device?> = repository.observeDevice(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val logs: StateFlow<List<ActivationLog>> = repository.observeRecentLogs(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _status = MutableStateFlow<TileStatus>(TileStatus.Loading)
    val status: StateFlow<TileStatus> = _status.asStateFlow()

    /** Simulation de présence réellement active (pour la gestion du conflit avec le minuteur). */
    private val _presenceActive = MutableStateFlow(false)
    val presenceActive: StateFlow<Boolean> = _presenceActive.asStateFlow()

    @Volatile
    private var permissionUsable: Boolean = true

    fun updatePermission(usable: Boolean) {
        permissionUsable = usable
    }

    fun refresh() {
        if (!permissionUsable) {
            _status.value = TileStatus.PermissionRequired
            return
        }
        viewModelScope.launch { fetch() }
    }

    /** Démarre le minuteur natif (autonome sur l'appareil), puis relit l'état réel. */
    fun startTimer(seconds: Int, detail: String) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.startTimer(dev, seconds, detail)
            fetch()
        }
    }

    fun cancelTimer() {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.cancelTimer(dev)
            fetch()
        }
    }

    /** Résolution du conflit : arrête la simulation de présence puis lance le minuteur. */
    fun stopPresenceThenStartTimer(seconds: Int, detail: String) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.stopPresence(dev)
            _presenceActive.value = false
            repository.startTimer(dev, seconds, detail)
            fetch()
        }
    }

    private suspend fun fetch() {
        val dev = repository.getDevice(deviceId) ?: return
        _status.value = repository.getStatus(dev).toTileStatus()
        if (dev.hasScripting) {
            repository.getPresenceState(dev).getOrNull()?.let { _presenceActive.value = it.running }
        }
    }
}
