package kapoue.hestia.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.widget.WidgetUpdater
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
    private val logger: DiagnosticLogger,
) : ViewModel() {

    private val statuses = MutableStateFlow<Map<Long, TileStatus>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)

    // Renseigné par la couche UI (qui seule connaît le Context) à chaque reprise d'écran.
    @Volatile
    private var permissionUsable: Boolean = true

    val uiState: StateFlow<DashboardUiState> =
        combine(repository.observeDevices(), statuses, refreshing, loaded) { devices, statusMap, isRefreshing, isLoaded ->
            DashboardUiState(
                tiles = devices.mapIndexed { index, device ->
                    TileUiState(
                        number = index + 1,
                        device = device,
                        status = statusMap[device.id] ?: TileStatus.Loading,
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
    fun refresh(userInitiated: Boolean = false) {
        // Ne pas empiler les cycles de polling ; un tirage manuel relance en priorité.
        if (!userInitiated && refreshJob?.isActive == true) return
        if (userInitiated) refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val devices = repository.getDevicesOnce()
            if (!permissionUsable) {
                statuses.value = devices.associate { it.id to TileStatus.PermissionRequired }
                loaded.value = true
                return@launch
            }
            val fetch = launch {
                val results = devices.map { device ->
                    async { device.id to repository.getStatus(device).toTileStatus() }
                }.awaitAll()
                statuses.value = results.toMap()
                loaded.value = true
                // Synchroniser les widgets d'écran d'accueil avec l'état relevé.
                runCatching {
                    WidgetUpdater.syncAll(
                        context,
                        results.associate { (id, status) -> id to (status as? TileStatus.Online)?.output },
                    )
                }
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
        viewModelScope.launch {
            when (repository.userToggle(device, turnOn)) {
                is RpcResult.Success -> {
                    logger.info(DiagnosticLogger.RPC, "Bascule ${device.ipAddress}#${device.switchId} → $turnOn")
                    fetchOne(device)
                }
                else -> setStatus(device.id, TileStatus.Offline)
            }
        }
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
