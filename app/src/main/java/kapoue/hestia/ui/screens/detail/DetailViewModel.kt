package kapoue.hestia.ui.screens.detail

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.R
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PausedPlanning
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.ui.navigation.StackedRoutes
import kapoue.hestia.ui.screens.dashboard.SensorStatus
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kapoue.hestia.ui.screens.dashboard.toSensorStatus
import kapoue.hestia.ui.screens.dashboard.toTileStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs

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

    /** Sans objet hors détecteur de fumée (`device.type == SMOKE_DETECTOR`) — voir SMOKE-DETECTOR.md. */
    private val _sensorStatus = MutableStateFlow<SensorStatus>(SensorStatus.Loading)
    val sensorStatus: StateFlow<SensorStatus> = _sensorStatus.asStateFlow()

    /** Coupure de prise en cas d'alarme (Lot 5) — cibles actuellement configurées sur le détecteur. */
    private val _cutoffTargets = MutableStateFlow<List<DeviceRepository.SmokeCutoffTarget>>(emptyList())
    val cutoffTargets: StateFlow<List<DeviceRepository.SmokeCutoffTarget>> = _cutoffTargets.asStateFlow()

    /** Appareils pouvant servir de cible de coupure (canaux avec relais, jamais le détecteur lui-même). */
    val cutoffCandidates: StateFlow<List<Device>> = repository.observeDevices()
        .map { devices -> devices.filter { it.supportsSwitch } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * Libellé du minuteur en cours (nom du préréglage Perso, ou durée lisible « 2h » pour un
     * lancement rapide/Manuel) — le même texte que celui choisi au démarrage ([PendingTimer.label]),
     * pour que l'écran affiche quel réglage pilote réellement la prise plutôt qu'un texte
     * générique (2026-08-17).
     */
    private val _pendingLabel = MutableStateFlow<String?>(null)
    val pendingLabel: StateFlow<String?> = _pendingLabel.asStateFlow()

    /**
     * Plannings réellement présents sur l'appareil, relus après chaque modification — précis et
     * simulations de présence confondus depuis la fusion du 2026-08-18 ([Planning.isPresence]).
     */
    private val _plannings = MutableStateFlow<List<Planning>>(emptyList())
    val plannings: StateFlow<List<Planning>> = _plannings.asStateFlow()

    /** Minuteur du bouton physique, réellement déployé sur l'appareil (jamais stocké par Hestia). */
    private val _buttonTimerConfig = MutableStateFlow<DeviceRepository.ButtonTimerConfig?>(null)
    val buttonTimerConfig: StateFlow<DeviceRepository.ButtonTimerConfig?> = _buttonTimerConfig.asStateFlow()

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
            // Aucune interrogation tentée : le bandeau global du Tableau porte le message de
            // permission manquante, pas cet écran (voir DashboardScreen).
            _status.value = TileStatus.Offline
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

    /**
     * Vrai si ce canal a été désactivé pour aujourd'hui via le bouton ON/OFF du Tableau (voir
     * `AppPreferences.isPresenceDisabledToday`) — lecture locale pure, pas de RPC. Sert à ne pas
     * afficher « En cours » sur une présence sciemment coupée pour la journée (retour David,
     * 2026-08-22 : l'écran Détail ne connaissait pas ce mémo, contrairement au Tableau).
     */
    fun isPresenceDisabledToday(): Boolean = appPreferences.isPresenceDisabledToday(deviceId)

    /** Même chose que [isPresenceDisabledToday], côté planning récurrent (voir
     * `AppPreferences.isPlanningDisabledToday`). */
    fun isPlanningDisabledToday(): Boolean = appPreferences.isPlanningDisabledToday(deviceId)

    /** Résolution du conflit : arrête la simulation de présence puis lance le minuteur. */
    fun stopPresenceThenStartTimer(seconds: Int?, detail: String, thresholdW: Int? = null) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.stopPresence(dev)
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
     * Unique. [cutoffThresholdW] : coupure sur seuil, réservée aux plannings précis (jamais avec
     * [marginMinutes]). [marginMinutes] non nul = simulation de présence plutôt que précis.
     */
    fun addPlanning(
        startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, days: Set<Int>,
        date: LocalDate? = null, cutoffThresholdW: Int? = null, marginMinutes: Int? = null,
    ) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val result = repository.createPlanning(dev, startHour, startMinute, endHour, endMinute, days, date, cutoffThresholdW, marginMinutes)
            _addPlanningResult.value = result
            if (result is CreatePlanningResult.Success) loadPlannings(dev)
        }
    }

    /** Modifie un planning existant ; même canal de résultat que l'ajout. */
    fun updatePlanning(
        old: Planning, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, days: Set<Int>,
        date: LocalDate? = null, cutoffThresholdW: Int? = null, marginMinutes: Int? = null,
    ) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val result = repository.updatePlanning(dev, old, startHour, startMinute, endHour, endMinute, days, date, cutoffThresholdW, marginMinutes)
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

    /** Coupe l'alarme sonore en cours (`Smoke.Mute`) puis relit l'état réel. */
    fun muteAlarm() {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.muteSmokeAlarm(dev)
            fetch()
        }
    }

    /** Remplace la configuration de coupure (Lot 5) par [targets], puis relit l'état réel appliqué. */
    fun setCutoffTargets(targets: List<DeviceRepository.SmokeCutoffTarget>) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            repository.setSmokeCutoffTargets(dev, targets)
            _cutoffTargets.value = repository.getSmokeCutoffTargets(dev)
        }
    }

    private suspend fun fetch() {
        val dev = repository.getDevice(deviceId) ?: return
        // Détecteur de fumée : chemin entièrement séparé, pas de relais ni de script (voir
        // SMOKE-DETECTOR.md) — Switch.GetStatus/plannings/minuteur bouton n'ont aucun sens ici.
        if (dev.type == DeviceType.SMOKE_DETECTOR) {
            _sensorStatus.value = repository.getSensorStatus(dev).toSensorStatus()
            _activeIp.value = repository.activeIp(dev)
            _cutoffTargets.value = repository.getSmokeCutoffTargets(dev)
            return
        }
        val status = repository.getStatus(dev).toTileStatus()
        _status.value = status
        _activeIp.value = repository.activeIp(dev)
        // Souvenir local (Manuel/Perso uniquement, voir DashboardViewModel.fetch) : ne le garder
        // que s'il correspond au minuteur natif réellement en cours sur l'appareil, sinon un
        // minuteur bouton (qui ne passe jamais par ce souvenir) afficherait un seuil/libellé
        // d'une tout autre programmation, périmée — bug vécu en direct le 2026-08-18.
        val pendingTimer = appPreferences.pendingTimers().firstOrNull { it.deviceId == deviceId }
        val deviceEndsAtElapsed = (status as? TileStatus.Online)?.timerEndsAtElapsed
        val matches = pendingTimer != null && deviceEndsAtElapsed != null &&
            abs(deviceEndsAtElapsed - SystemClock.elapsedRealtime() - (pendingTimer.endMillis - System.currentTimeMillis())) <= STALE_PENDING_TIMER_TOLERANCE_MS
        if (pendingTimer != null && !matches) appPreferences.removePendingTimer(deviceId)
        _pendingThresholdW.value = pendingTimer?.thresholdW.takeIf { matches }
        _pendingLabel.value = pendingTimer?.label.takeIf { matches }
        if (dev.hasScripting) {
            loadButtonTimer(dev)
        }
        // getPlannings fusionne plannings précis et simulations de présence (gated en interne sur
        // hasScripting pour ces dernières) depuis la fusion du 2026-08-18.
        if (dev.supportsSwitch) loadPlannings(dev)
    }

    private suspend fun loadPlannings(dev: Device) {
        repository.getPlannings(dev).getOrNull()?.let { _plannings.value = it }
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

    private companion object {
        /** Tolérance pour considérer qu'un souvenir local de minuteur correspond bien au minuteur
         * natif actuellement en cours sur l'appareil (voir le calcul dans [fetch]). */
        const val STALE_PENDING_TIMER_TOLERANCE_MS = 5_000L
    }
}
