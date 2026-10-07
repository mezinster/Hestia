package kapoue.hestia.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.local.entity.PausedPlanning
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.CoverRepository
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.repository.LightRepository
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.domain.model.CreatePlanningResult
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.ui.common.UserMessage
import kapoue.hestia.ui.common.toUserMessageOrNull
import kapoue.hestia.ui.navigation.StackedRoutes
import kapoue.hestia.ui.screens.dashboard.CoverStatus
import kapoue.hestia.ui.screens.dashboard.LightStatus
import kapoue.hestia.ui.screens.dashboard.SensorStatus
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kapoue.hestia.ui.screens.dashboard.coverPollIntervalMs
import kapoue.hestia.ui.screens.dashboard.toCoverStatus
import kapoue.hestia.ui.screens.dashboard.toLightStatus
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

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: DeviceRepository,
    private val appPreferences: AppPreferences,
    private val lightRepository: LightRepository,
    private val coverRepository: CoverRepository,
    private val logger: DiagnosticLogger,
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

    /**
     * Coupure de prise en cas d'alarme (Lot 5) — état actuellement configuré sur le détecteur.
     * [DeviceRepository.SmokeCutoffState.Unknown] tant qu'il n'a pas été lu avec succès au moins
     * une fois (détecteur endormi la plupart du temps) : à ne jamais confondre avec une coupure
     * désactivée, sous peine de laisser croire qu'un réglage tout juste fait n'a pas pris.
     */
    private val _cutoffState = MutableStateFlow<DeviceRepository.SmokeCutoffState>(DeviceRepository.SmokeCutoffState.Unknown)
    val cutoffState: StateFlow<DeviceRepository.SmokeCutoffState> = _cutoffState.asStateFlow()

    /**
     * Message transitoire si la coupure n'a pas pu être enregistrée — quasi toujours parce que le
     * détecteur dort (contrairement aux prises, jamais en ligne en permanence) : réglage écrit
     * directement sur ses webhooks natifs, impossible s'il est injoignable à cet instant précis.
     */
    private val _cutoffMessage = MutableStateFlow<UserMessage?>(null)
    val cutoffMessage: StateFlow<UserMessage?> = _cutoffMessage.asStateFlow()

    fun consumeCutoffMessage() {
        _cutoffMessage.value = null
    }

    /** État du variateur, seulement pour un canal light (variateurs, 2026-10-07). */
    private val _lightStatus = MutableStateFlow<LightStatus>(LightStatus.Loading)
    val lightStatus: StateFlow<LightStatus> = _lightStatus.asStateFlow()

    /** Incrémenté après chaque commande (succès ou échec) : force le recalage du curseur. */
    private val _lightRevision = MutableStateFlow(0)
    val lightRevision: StateFlow<Int> = _lightRevision.asStateFlow()

    private val _lightError = MutableStateFlow<UserMessage?>(null)
    val lightError: StateFlow<UserMessage?> = _lightError.asStateFlow()
    fun consumeLightError() { _lightError.value = null }

    /** État du volet, seulement pour un canal cover (volets, 2026-10-07). */
    private val _coverStatus = MutableStateFlow<CoverStatus>(CoverStatus.Loading)
    val coverStatus: StateFlow<CoverStatus> = _coverStatus.asStateFlow()

    /** Incrémenté après chaque commande (succès ou échec) : force le recalage du curseur de position. */
    private val _coverRevision = MutableStateFlow(0)
    val coverRevision: StateFlow<Int> = _coverRevision.asStateFlow()

    private val _coverError = MutableStateFlow<UserMessage?>(null)
    val coverError: StateFlow<UserMessage?> = _coverError.asStateFlow()
    fun consumeCoverError() { _coverError.value = null }

    /** Cadence de relevé de l'écran : rapide pendant un mouvement de volet, 5 s sinon (relais, variateurs). */
    fun pollIntervalMs(): Long =
        if (device.value?.isCover == true) coverPollIntervalMs(_coverStatus.value) else 5_000L

    /** Appareils pouvant servir de cible de coupure (canaux avec relais, jamais le détecteur lui-même). */
    val cutoffCandidates: StateFlow<List<Device>> = repository.observeDevices()
        .map { devices -> devices.filter { it.supportsSwitch } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** IP effectivement utilisée lors du dernier appel réussi (1ᵉʳ ou 2ᵉ emplacement). */
    private val _activeIp = MutableStateFlow<String?>(null)
    val activeIp: StateFlow<String?> = _activeIp.asStateFlow()

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
            _lightStatus.value = LightStatus.Offline
            _coverStatus.value = CoverStatus.Offline
            return
        }
        viewModelScope.launch { fetch() }
    }

    /**
     * Enregistre (ou remplace) l'un des deux réglages personnalisés du minuteur ([slot] = 1 ou
     * 2) — confort propre à Hestia, jamais envoyé à la prise avant que l'utilisateur ne le lance
     * via sa puce nommée. [seconds] null = sans limite de durée ; [thresholdW] null = sans
     * coupure sur seuil ; les deux peuvent être absents à la fois (retour David, 2026-09-11).
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

    /**
     * Remplace la configuration de coupure (Lot 5) par [targets], puis relit l'état réel appliqué
     * (jamais supposé écrit avec succès juste parce que l'appel est parti). Signale un message si
     * le détecteur n'a pas pu être joint — sinon la case cochée se réinitialiserait sans
     * explication, comme si le clic n'avait rien fait (retour David, 2026-09-03).
     */
    fun setCutoffTargets(targets: List<DeviceRepository.SmokeCutoffTarget>) {
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            val result = repository.setSmokeCutoffTargets(dev, targets)
            if (result !is RpcResult.Success) {
                _cutoffMessage.value = UserMessage(R.string.sensor_cutoff_unreachable)
            }
            _cutoffState.value = repository.getSmokeCutoffState(dev)
        }
    }

    private suspend fun fetch() {
        val dev = repository.getDevice(deviceId) ?: return
        // Détecteur de fumée : chemin entièrement séparé, pas de relais ni de script (voir
        // SMOKE-DETECTOR.md) — Switch.GetStatus/plannings/minuteur bouton n'ont aucun sens ici.
        if (dev.type == DeviceType.SMOKE_DETECTOR) {
            _sensorStatus.value = repository.getSensorStatus(dev).toSensorStatus()
            _activeIp.value = repository.activeIp(dev)
            _cutoffState.value = repository.getSmokeCutoffState(dev)
            return
        }
        if (dev.isCover) {
            _coverStatus.value = coverRepository.getStatus(dev).toCoverStatus()
            _activeIp.value = repository.activeIp(dev)
            return
        }
        if (dev.isLight) {
            _lightStatus.value = lightRepository.getStatus(dev).toLightStatus()
            _activeIp.value = repository.activeIp(dev)
            loadPlannings(dev)
            return
        }
        val status = repository.getStatus(dev).toTileStatus()
        _status.value = status
        _activeIp.value = repository.activeIp(dev)
        // Le souvenir local du minuteur en cours (Manuel/Perso) n'est plus affiché sur cet écran
        // depuis l'ergonomie à deux niveaux du 2026-09-21 (voir TimerSection) — sa correspondance
        // avec le minuteur natif réel, et le nettoyage s'il est périmé, restent gérés par
        // DashboardViewModel.fetch, qui interroge de toute façon déjà chaque appareil en continu.
        if (dev.hasScripting) {
            loadButtonTimer(dev)
        }
        // getPlannings fusionne plannings précis et simulations de présence (gated en interne sur
        // hasScripting pour ces dernières) depuis la fusion du 2026-08-18.
        if (dev.supportsSwitch) loadPlannings(dev)
    }

    /** Marche/arrêt ou luminosité d'un variateur ; relit l'état réel ensuite, succès ou non. */
    /** [toggleAfterSec] non nul : minuteur tenu par l'appareil (C1), qui s'éteint seul ensuite. */
    fun setLight(on: Boolean?, brightness: Int?, toggleAfterSec: Int? = null) {
        if (!permissionUsable) return
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            logger.info(
                DiagnosticLogger.UI,
                "Variateur ${dev.ipAddress}#${dev.switchId} → on=$on luminosité=${brightness ?: "-"}" +
                    (toggleAfterSec?.let { " minuteur=${it}s" } ?: ""),
            )
            val result = lightRepository.set(dev, on, brightness, toggleAfterSec)
            if (result !is RpcResult.Success) _lightError.value = result.toUserMessageOrNull()
            _lightStatus.value = lightRepository.getStatus(dev).toLightStatus()
            _lightRevision.value += 1
        }
    }

    fun coverOpen() = coverAction("ouverture") { coverRepository.open(it) }
    fun coverClose() = coverAction("fermeture") { coverRepository.close(it) }
    fun coverStop() = coverAction("arrêt") { coverRepository.stop(it) }
    fun coverGoTo(pos: Int) = coverAction("position=$pos") { coverRepository.goTo(it, pos) }
    fun coverCalibrate() = coverAction("calibration") { coverRepository.calibrate(it) }

    /** Commande de volet : journalise, appelle, signale un échec, puis relit l'état réel (succès ou non). */
    private fun coverAction(label: String, call: suspend (Device) -> RpcResult<*>) {
        if (!permissionUsable) return
        viewModelScope.launch {
            val dev = repository.getDevice(deviceId) ?: return@launch
            logger.info(DiagnosticLogger.UI, "Volet ${dev.ipAddress}#${dev.switchId} → $label")
            val result = call(dev)
            if (result !is RpcResult.Success) _coverError.value = result.toUserMessageOrNull()
            _coverStatus.value = coverRepository.getStatus(dev).toCoverStatus()
            _coverRevision.value += 1
        }
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
}
