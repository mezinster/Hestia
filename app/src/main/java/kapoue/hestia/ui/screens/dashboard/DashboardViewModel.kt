package kapoue.hestia.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.notifications.NtfyClient
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.isActiveNow
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DeviceRepository,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
    private val ntfyClient: NtfyClient,
) : ViewModel() {

    /**
     * Outil de support (pas une fonctionnalité) : envoie d'un coup tous les textes de notif
     * possibles, pour les relire et les valider sans attendre que chaque cas réel se produise.
     * Déclenché par 3 appuis rapprochés sur le titre du Tableau — jamais visible ailleurs.
     */
    fun debugSendAllNtfyTexts() {
        if (!appPreferences.ntfyEnabled.value) return
        val topic = appPreferences.ntfyTopic.value ?: return
        viewModelScope.launch {
            val title = "Hestia (test)"
            listOf(
                context.getString(R.string.notif_planning_started, "09:00", "17:00"),
                context.getString(R.string.notif_planning_ended, "09:00", "17:00"),
                context.getString(R.string.notif_ntfy_presence_started),
                context.getString(R.string.notif_ntfy_presence_ended),
                context.getString(R.string.notif_timer_ended, "30 min"),
                context.getString(R.string.notif_cutoff_triggered),
            ).forEach { body ->
                ntfyClient.send(topic, title, body)
                delay(1_500)
            }
        }
    }

    private val statuses = MutableStateFlow<Map<Long, TileStatus>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)

    // Simulations de présence réellement en cours, relevées sur les appareils à chaque cycle.
    private val presences = MutableStateFlow<Map<Long, PresenceInfo>>(emptyMap())

    // Plannings présents sur chaque appareil (la tuile affiche celui en cours, le cas échéant).
    private val plannings = MutableStateFlow<Map<Long, List<Planning>>>(emptyMap())

    // Seuil du minuteur en attente par appareil (mémo local, lecture instantanée — pas de RPC).
    // La tuile ne l'affiche que si l'appareil confirme lui-même un minuteur en cours.
    private val pendingThresholds = MutableStateFlow<Map<Long, Int>>(emptyMap())

    // Renseigné par la couche UI (qui seule connaît le Context) à chaque reprise d'écran.
    @Volatile
    private var permissionUsable: Boolean = true

    // combine plafonne à 5 flux typés : on regroupe présence + planning + seuils en un seul.
    private val extras = combine(presences, plannings, pendingThresholds) { p, pl, th -> Triple(p, pl, th) }

    val uiState: StateFlow<DashboardUiState> =
        combine(repository.observeDevices(), statuses, refreshing, loaded, extras) { devices, statusMap, isRefreshing, isLoaded, (presenceMap, planningMap, thresholdMap) ->
            DashboardUiState(
                tiles = devices.mapIndexed { index, device ->
                    TileUiState(
                        number = index + 1,
                        device = device,
                        status = statusMap[device.id] ?: TileStatus.Loading,
                        presence = presenceMap[device.id],
                        plannings = planningMap[device.id].orEmpty(),
                        pendingThresholdW = thresholdMap[device.id],
                    )
                },
                isRefreshing = isRefreshing,
                loaded = isLoaded,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** Mis à jour à chaque reprise d'écran. Si la permission tombe, les tuiles le reflètent. */
    fun updatePermission(usable: Boolean) {
        permissionUsable = usable
    }

    private var refreshJob: Job? = null

    /**
     * Interroge tous les appareils en parallèle. Ne relance jamais tout seul en boucle.
     *
     * L'indicateur de rafraîchissement apparaît immédiatement pour un **tirage manuel**
     * (`userInitiated`). Pour un cycle **automatique** (arrivée, polling), il n'apparaît que
     * si la lecture traîne (> [INDICATOR_DELAY_MS]) : quand les appareils répondent vite, la
     * lecture se termine avant et aucun picto ne clignote ; quand ils ne répondent pas, le
     * picto s'affiche et joue son rôle de « recherche en cours ».
     */
    fun refresh(userInitiated: Boolean = false, force: Boolean = false) {
        // Ne pas empiler les cycles de polling ; un tirage manuel (ou un relevé forcé) relance
        // en priorité. [force] sert p. ex. à confirmer vite l'extinction en fin de créneau de
        // planning, sans afficher le spinner immédiatement (indicateur temporisé comme un cycle
        // automatique).
        if (!userInitiated && !force && refreshJob?.isActive == true) return
        if (userInitiated || force) refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val devices = repository.getDevicesOnce()
            if (!permissionUsable) {
                statuses.value = devices.associate { it.id to TileStatus.PermissionRequired }
                loaded.value = true
                return@launch
            }
            val fetch = launch {
                // États, présences et plannings relevés en parallèle : le temps total reste celui
                // du plus lent, pas la somme.
                val statusResults = async {
                    devices.map { device ->
                        async { device.id to repository.getStatus(device).toTileStatus() }
                    }.awaitAll()
                }
                val presenceResults = async {
                    devices.filter { it.hasScripting }.map { device ->
                        async { device.id to loadPresence(device) }
                    }.awaitAll()
                }
                val planningResults = async {
                    devices.filter { it.supportsSwitch }.map { device ->
                        async { device.id to repository.getPlannings(device).getOrNull().orEmpty() }
                    }.awaitAll()
                }
                statuses.value = statusResults.await().toMap()
                presences.value = presenceResults.await()
                    .mapNotNull { (id, info) -> info?.let { id to it } }
                    .toMap()
                plannings.value = planningResults.await().toMap()
                // Lecture locale (SharedPreferences), pas de RPC : pas besoin de la paralléliser.
                pendingThresholds.value = appPreferences.pendingTimers()
                    .mapNotNull { timer -> timer.thresholdW?.let { timer.deviceId to it } }
                    .toMap()
                loaded.value = true
            }
            val indicator = if (userInitiated) {
                refreshing.value = true
                null
            } else {
                launch {
                    delay(INDICATOR_DELAY_MS)
                    if (isActive) refreshing.value = true
                }
            }
            fetch.join()
            indicator?.cancel()
            refreshing.value = false
        }
    }

    /** Bascule un canal, puis relit son état réel (jamais supposé). */
    fun toggle(device: Device, turnOn: Boolean) {
        if (!permissionUsable) return
        viewModelScope.launch { applyToggle(device, turnOn) }
    }

    /**
     * Arrête la simulation de présence **puis** applique la bascule demandée.
     *
     * Sans cela, éteindre une prise pilotée par le script n'aurait qu'un effet fugace : le
     * programme la rallumerait quelques instants plus tard, donnant l'impression d'un
     * interrupteur défaillant.
     */
    fun stopPresenceThenToggle(device: Device, turnOn: Boolean) {
        if (!permissionUsable) return
        viewModelScope.launch {
            when (repository.stopPresence(device)) {
                is RpcResult.Success -> {
                    logger.info(DiagnosticLogger.RPC, "Simulation de présence arrêtée depuis le Tableau (${device.ipAddress})")
                    presences.value = presences.value - device.id
                    applyToggle(device, turnOn)
                }
                else -> setStatus(device.id, TileStatus.Offline)
            }
        }
    }

    private suspend fun applyToggle(device: Device, turnOn: Boolean) {
        when (repository.userToggle(device, turnOn)) {
            is RpcResult.Success -> {
                logger.info(DiagnosticLogger.RPC, "Bascule ${device.ipAddress}#${device.switchId} → $turnOn")
                fetchOne(device)
            }
            else -> setStatus(device.id, TileStatus.Offline)
        }
    }

    /**
     * Simulation de présence en cours sur cet appareil, ou null. L'exécution est vérifiée sur
     * l'appareil ; les horaires viennent du cache local (ils n'ont d'intérêt qu'affichés).
     */
    private suspend fun loadPresence(device: Device): PresenceInfo? {
        // Plages lues depuis la prise (jamais supposées) ; on affiche celle en cours, s'il y en a.
        val windows = repository.getPresenceWindows(device).getOrNull().orEmpty()
        val active = windows.firstOrNull { it.isActiveNow() } ?: return null
        return PresenceInfo(active.startHour, active.startMinute, active.endHour, active.endMinute)
    }

    /**
     * Mode démo (build debug uniquement) : ajoute/retire des appareils fictifs pour les
     * captures d'écran. Déclenché par un appui long sur le titre « Hestia ».
     */
    fun toggleDemo() {
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        viewModelScope.launch {
            if (repository.hasDemoDevices()) repository.removeDemoDevices() else repository.addDemoDevices()
        }
    }

    /** Relance la lecture d'un seul appareil (bouton « Réessayer » d'une tuile hors ligne). */
    fun retry(device: Device) {
        if (!permissionUsable) {
            setStatus(device.id, TileStatus.PermissionRequired)
            return
        }
        viewModelScope.launch { fetchOne(device) }
    }

    private suspend fun fetchOne(device: Device) {
        setStatus(device.id, repository.getStatus(device).toTileStatus())
    }

    private fun setStatus(deviceId: Long, status: TileStatus) {
        statuses.value = statuses.value + (deviceId to status)
    }

    private companion object {
        const val INDICATOR_DELAY_MS = 600L
    }
}
