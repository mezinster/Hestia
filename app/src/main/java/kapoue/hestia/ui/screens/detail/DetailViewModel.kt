package kapoue.hestia.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.data.local.entity.ActivationLog
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.PresenceOpResult
import kapoue.hestia.domain.model.PresenceWindow
import kapoue.hestia.domain.model.isActiveNow
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

    /** Plages de présence réellement embarquées dans le script, relues après chaque modification. */
    private val _presenceWindows = MutableStateFlow<List<PresenceWindow>>(emptyList())
    val presenceWindows: StateFlow<List<PresenceWindow>> = _presenceWindows.asStateFlow()

    /** Résultat de la dernière tentative d'ajout/édition de plage présence (conflit…). */
    private val _addPresenceResult = MutableStateFlow<PresenceOpResult?>(null)
    val addPresenceResult: StateFlow<PresenceOpResult?> = _addPresenceResult.asStateFlow()

    /** Plannings réellement présents sur l'appareil, relus après chaque modification. */
    private val _plannings = MutableStateFlow<List<Planning>>(emptyList())
    val plannings: StateFlow<List<Planning>> = _plannings.asStateFlow()

    /** Résultat de la dernière tentative d'ajout (conflit, limite…), consommé par l'UI. */
    private val _addPlanningResult = MutableStateFlow<CreatePlanningResult?>(null)
    val addPlanningResult: StateFlow<CreatePlanningResult?> = _addPlanningResult.asStateFlow()

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

    /**
     * Démarre le minuteur (autonome sur l'appareil), puis relit l'état réel. Si [thresholdW] est
     * fourni, ajoute la coupure sur seuil de consommation.
     */
    fun startTimer(seconds: Int, detail: String, thresholdW: Int? = null) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            if (thresholdW != null) repository.startChargeTimer(dev, seconds, thresholdW, detail)
            else repository.startTimer(dev, seconds, detail)
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
    fun stopPresenceThenStartTimer(seconds: Int, detail: String, thresholdW: Int? = null) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.stopPresence(dev)
            _presenceActive.value = false
            _presenceWindows.value = emptyList()
            if (thresholdW != null) repository.startChargeTimer(dev, seconds, thresholdW, detail)
            else repository.startTimer(dev, seconds, detail)
            fetch()
        }
    }

    /** Ajoute un planning ; le résultat (succès ou conflit) est publié pour l'UI. */
    fun addPlanning(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, days: Set<Int>) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val result = repository.createPlanning(dev, startHour, startMinute, endHour, endMinute, days)
            _addPlanningResult.value = result
            if (result is CreatePlanningResult.Success) loadPlannings(dev)
        }
    }

    /** Modifie un planning existant ; même canal de résultat que l'ajout. */
    fun updatePlanning(old: Planning, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, days: Set<Int>) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val result = repository.updatePlanning(dev, old, startHour, startMinute, endHour, endMinute, days)
            _addPlanningResult.value = result
            if (result is CreatePlanningResult.Success) loadPlannings(dev)
        }
    }

    /** Réinitialise le résultat d'ajout (à l'ouverture/fermeture du dialogue). */
    fun clearAddPlanningResult() {
        _addPlanningResult.value = null
    }

    fun deletePlanning(planning: Planning) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.deletePlanning(dev, planning)
            loadPlannings(dev)
        }
    }

    /** Ajoute une plage de présence ; refusée si elle chevauche un planning. */
    fun addPresenceWindow(window: PresenceWindow) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            if (repository.presenceConflictsWithPlanning(dev, window)) {
                _addPresenceResult.value = PresenceOpResult.PlanningOverlap
                return@launch
            }
            applyPresence(dev, _presenceWindows.value + window)
        }
    }

    fun updatePresenceWindow(old: PresenceWindow, new: PresenceWindow) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            if (repository.presenceConflictsWithPlanning(dev, new)) {
                _addPresenceResult.value = PresenceOpResult.PlanningOverlap
                return@launch
            }
            applyPresence(dev, _presenceWindows.value.map { if (it == old) new else it })
        }
    }

    private suspend fun applyPresence(dev: Device, windows: List<PresenceWindow>) {
        val result = repository.setPresenceWindows(dev, windows)
        _addPresenceResult.value = if (result is RpcResult.Success) PresenceOpResult.Success else PresenceOpResult.Error
        if (result is RpcResult.Success) loadPresence(dev)
    }

    fun clearAddPresenceResult() {
        _addPresenceResult.value = null
    }

    /** Supprime une plage ; si elle est en cours, éteint la prise dans la foulée. */
    fun deletePresenceWindow(window: PresenceWindow) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.setPresenceWindows(dev, _presenceWindows.value - window)
            if (window.isActiveNow()) repository.userToggle(dev, false)
            loadPresence(dev)
            fetch()
        }
    }

    private suspend fun fetch() {
        val dev = repository.getDevice(deviceId) ?: return
        _status.value = repository.getStatus(dev).toTileStatus()
        if (dev.hasScripting) loadPresence(dev)
        if (dev.supportsSwitch) loadPlannings(dev)
    }

    private suspend fun loadPlannings(dev: Device) {
        repository.getPlannings(dev).getOrNull()?.let { _plannings.value = it }
    }

    private suspend fun loadPresence(dev: Device) {
        val windows = repository.getPresenceWindows(dev).getOrNull().orEmpty()
        _presenceWindows.value = windows
        _presenceActive.value = windows.isNotEmpty()
    }
}
