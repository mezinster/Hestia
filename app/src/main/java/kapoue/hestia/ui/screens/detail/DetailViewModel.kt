package kapoue.hestia.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.R
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PausedPlanning
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.FirmwareCheckResult
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.PresenceOpResult
import kapoue.hestia.domain.model.PresenceWindow
import kapoue.hestia.domain.model.isActiveNow
import kapoue.hestia.ui.common.UserMessage
import kapoue.hestia.ui.navigation.StackedRoutes
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kapoue.hestia.ui.screens.dashboard.toTileStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: DeviceRepository,
    private val appPreferences: AppPreferences,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val deviceId: Long = checkNotNull(savedStateHandle.get<Long>(StackedRoutes.DETAIL_ARG_ID))

    val device: StateFlow<Device?> = repository.observeDevice(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _status = MutableStateFlow<TileStatus>(TileStatus.Loading)
    val status: StateFlow<TileStatus> = _status.asStateFlow()

    /** IP effectivement utilisée lors du dernier appel réussi (1ᵉʳ ou 2ᵉ emplacement). */
    private val _activeIp = MutableStateFlow<String?>(null)
    val activeIp: StateFlow<String?> = _activeIp.asStateFlow()

    /**
     * Seuil du minuteur actuellement en attente pour cet appareil, ou null. Lu à chaque relevé ;
     * l'écran ne l'affiche que si l'appareil confirme lui-même un minuteur en cours (voir
     * [status]) — ce mémo peut rester en place un moment après la fin réelle du minuteur.
     */
    private val _pendingThresholdW = MutableStateFlow<Int?>(null)
    val pendingThresholdW: StateFlow<Int?> = _pendingThresholdW.asStateFlow()

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

    /** Minuteur du bouton physique, réellement déployé sur l'appareil (jamais stocké par Hestia). */
    private val _buttonTimerConfig = MutableStateFlow<DeviceRepository.ButtonTimerConfig?>(null)
    val buttonTimerConfig: StateFlow<DeviceRepository.ButtonTimerConfig?> = _buttonTimerConfig.asStateFlow()

    /** Résultat de la dernière tentative d'ajout (conflit, limite…), consommé par l'UI. */
    private val _addPlanningResult = MutableStateFlow<CreatePlanningResult?>(null)
    val addPlanningResult: StateFlow<CreatePlanningResult?> = _addPlanningResult.asStateFlow()

    /** Résultat de la dernière vérification manuelle de mise à jour firmware. */
    private val _firmwareCheck = MutableStateFlow<FirmwareCheckResult?>(null)
    val firmwareCheck: StateFlow<FirmwareCheckResult?> = _firmwareCheck.asStateFlow()

    private val _firmwareChecking = MutableStateFlow(false)
    val firmwareChecking: StateFlow<Boolean> = _firmwareChecking.asStateFlow()

    private val _firmwareInstalling = MutableStateFlow(false)
    val firmwareInstalling: StateFlow<Boolean> = _firmwareInstalling.asStateFlow()

    /** Message transitoire de l'installation (démarrée ou en échec), consommé par l'UI. */
    private val _firmwareInstallMessage = MutableStateFlow<UserMessage?>(null)
    val firmwareInstallMessage: StateFlow<UserMessage?> = _firmwareInstallMessage.asStateFlow()

    fun consumeFirmwareInstallMessage() {
        _firmwareInstallMessage.value = null
    }

    /** Vérification manuelle (seul appel du projet qui sort du réseau local). */
    fun checkFirmwareUpdate() {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            _firmwareChecking.value = true
            _firmwareInstallMessage.value = null
            _firmwareCheck.value = repository.checkFirmwareUpdate(dev)
            _firmwareChecking.value = false
        }
    }

    /** Démarre l'installation de la mise à jour stable ; l'appareil redémarre pour l'appliquer. */
    fun installFirmwareUpdate() {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            _firmwareInstalling.value = true
            val result = repository.installFirmwareUpdate(dev)
            _firmwareInstalling.value = false
            _firmwareInstallMessage.value = if (result is RpcResult.Success) {
                _firmwareCheck.value = null // Redémarrage à venir : l'état vérifié devient obsolète.
                UserMessage(R.string.firmware_install_started)
            } else {
                UserMessage(R.string.firmware_install_error)
            }
        }
    }

    private val _rebooting = MutableStateFlow(false)
    val rebooting: StateFlow<Boolean> = _rebooting.asStateFlow()

    /** Message transitoire du redémarrage (lancé ou en échec), consommé par l'UI. */
    private val _rebootMessage = MutableStateFlow<UserMessage?>(null)
    val rebootMessage: StateFlow<UserMessage?> = _rebootMessage.asStateFlow()

    fun consumeRebootMessage() {
        _rebootMessage.value = null
    }

    /** Redémarre l'appareil (dépannage) ; jamais bloqué par un minuteur en cours. */
    fun rebootDevice() {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            _rebooting.value = true
            val result = repository.rebootDevice(dev)
            _rebooting.value = false
            _rebootMessage.value = if (result is RpcResult.Success) {
                _firmwareCheck.value = null // Redémarrage à venir : l'état vérifié devient obsolète.
                UserMessage(R.string.firmware_reboot_started)
            } else {
                UserMessage(R.string.firmware_reboot_error)
            }
        }
    }

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
     * fourni, ajoute la coupure sur seuil de consommation. [seconds] null = sans limite de durée
     * (coupure sur seuil uniquement, [thresholdW] alors obligatoire, imposé côté écran).
     */
    fun startTimer(seconds: Int?, detail: String, thresholdW: Int? = null) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            when {
                seconds == null && thresholdW != null -> repository.startUnlimitedChargeTimer(dev, thresholdW)
                seconds != null && thresholdW != null -> repository.startChargeTimer(dev, seconds, thresholdW, detail)
                seconds != null -> repository.startTimer(dev, seconds, detail)
                else -> Unit // sans durée ni seuil : rien à lancer, ne devrait pas arriver (imposé côté écran).
            }
            fetch()
        }
    }

    /**
     * Enregistre (ou remplace) l'un des deux réglages personnalisés du minuteur ([slot] = 1 ou
     * 2) — confort propre à Hestia, jamais envoyé à la prise avant que l'utilisateur ne le lance
     * via sa puce nommée. [seconds] null = sans limite de durée ([thresholdW] alors obligatoire,
     * imposé côté écran).
     */
    fun savePreset(slot: Int, name: String, seconds: Int?, thresholdW: Int?) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val updated = if (slot == 1) {
                dev.copy(presetName = name, presetDurationSeconds = seconds, presetThresholdW = thresholdW, presetUnlimited = seconds == null)
            } else {
                dev.copy(preset2Name = name, preset2DurationSeconds = seconds, preset2ThresholdW = thresholdW, preset2Unlimited = seconds == null)
            }
            repository.updateDevice(updated)
        }
    }

    /** Supprime le réglage de l'emplacement [slot] ; sa puce et sa ligne disparaissent. */
    fun deletePreset(slot: Int) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val updated = if (slot == 1) {
                dev.copy(presetName = null, presetDurationSeconds = null, presetThresholdW = null, presetUnlimited = false)
            } else {
                dev.copy(preset2Name = null, preset2DurationSeconds = null, preset2ThresholdW = null, preset2Unlimited = false)
            }
            repository.updateDevice(updated)
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
    fun stopPresenceThenStartTimer(seconds: Int?, detail: String, thresholdW: Int? = null) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.stopPresence(dev)
            _presenceActive.value = false
            _presenceWindows.value = emptyList()
            when {
                seconds == null && thresholdW != null -> repository.startUnlimitedChargeTimer(dev, thresholdW)
                seconds != null && thresholdW != null -> repository.startChargeTimer(dev, seconds, thresholdW, detail)
                seconds != null -> repository.startTimer(dev, seconds, detail)
                else -> Unit
            }
            fetch()
        }
    }

    /**
     * Ajoute un planning ; le résultat (succès ou conflit) est publié pour l'UI. [date] non nul =
     * Unique. [cutoffThresholdW] : coupure sur seuil, réservée aux plannings Unique.
     */
    fun addPlanning(
        startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, days: Set<Int>,
        date: LocalDate? = null, cutoffThresholdW: Int? = null,
    ) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val result = repository.createPlanning(dev, startHour, startMinute, endHour, endMinute, days, date, cutoffThresholdW)
            _addPlanningResult.value = result
            if (result is CreatePlanningResult.Success) loadPlannings(dev)
        }
    }

    /** Modifie un planning existant ; même canal de résultat que l'ajout. */
    fun updatePlanning(
        old: Planning, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, days: Set<Int>,
        date: LocalDate? = null, cutoffThresholdW: Int? = null,
    ) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val result = repository.updatePlanning(dev, old, startHour, startMinute, endHour, endMinute, days, date, cutoffThresholdW)
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

    /** Plannings mis en pause pour cet appareil (mémo local, voir [DeviceRepository.pausePlanning]). */
    val pausedPlannings: StateFlow<List<PausedPlanning>> = repository.observePausedPlannings(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Résultat d'une réactivation ratée (conflit, appareil injoignable…), consommé par l'UI. */
    private val _resumePlanningError = MutableStateFlow<CreatePlanningResult?>(null)
    val resumePlanningError: StateFlow<CreatePlanningResult?> = _resumePlanningError.asStateFlow()

    fun consumeResumePlanningError() {
        _resumePlanningError.value = null
    }

    fun pausePlanning(planning: Planning) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.pausePlanning(dev, planning)
            loadPlannings(dev)
        }
    }

    fun resumePlanning(paused: PausedPlanning) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val result = repository.resumePlanning(dev, paused)
            if (result !is CreatePlanningResult.Success) {
                _resumePlanningError.value = result
            } else {
                loadPlannings(dev)
            }
        }
    }

    fun deletePausedPlanning(paused: PausedPlanning) {
        viewModelScope.launch { repository.deletePausedPlanning(paused) }
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
        _activeIp.value = repository.activeIp(dev)
        _pendingThresholdW.value = appPreferences.pendingTimers().firstOrNull { it.deviceId == deviceId }?.thresholdW
        if (dev.hasScripting) {
            loadPresence(dev)
            loadButtonTimer(dev)
        }
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

    private suspend fun loadButtonTimer(dev: Device) {
        _buttonTimerConfig.value = repository.getButtonTimerConfig(dev)
    }

    /** Active/reconfigure ou désactive le minuteur du bouton physique. [durationSeconds] null = sans limite de durée. */
    fun setButtonTimer(enabled: Boolean, durationSeconds: Int?, thresholdW: Int?) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.setButtonTimer(dev, enabled, durationSeconds, thresholdW)
            loadButtonTimer(dev)
        }
    }
}
