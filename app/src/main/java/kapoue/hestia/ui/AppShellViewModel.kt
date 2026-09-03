package kapoue.hestia.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.ui.navigation.SettingsScrollCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * État de la coquille de l'application (voir [HestiaApp]), distinct des écrans eux-mêmes —
 * aujourd'hui uniquement le bandeau de couverture zéro du relais ntfy des détecteurs de fumée
 * (Lot 4c, voir SMOKE-DETECTOR.md).
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    repository: DeviceRepository,
    appPreferences: AppPreferences,
    private val scrollCoordinator: SettingsScrollCoordinator,
) : ViewModel() {

    /**
     * Vrai si au moins un détecteur de fumée est présent, ntfy activé, **et** qu'aucun appareil
     * ne peut actuellement relayer ses alertes (dernier résultat connu, voir
     * `AppPreferences.smokeRelayCoverageOk`) — jamais recalculé ici, seulement observé.
     */
    val showSmokeRelayBanner: StateFlow<Boolean> = combine(
        repository.observeDevices(),
        appPreferences.ntfyEnabled,
        appPreferences.smokeRelayCoverageOk,
    ) { devices, ntfyEnabled, coverageOk ->
        ntfyEnabled && !coverageOk && devices.any { it.type == DeviceType.SMOKE_DETECTOR }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Appelé au clic sur le bandeau, juste avant de naviguer vers Réglages. */
    fun onSmokeRelayBannerClicked() {
        scrollCoordinator.requestScrollToNtfy()
    }
}
