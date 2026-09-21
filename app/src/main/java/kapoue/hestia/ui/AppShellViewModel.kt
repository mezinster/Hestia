package kapoue.hestia.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.ui.navigation.SettingsScrollCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
     * Délai de grâce au lancement (retour David, 2026-09-18) : `smokeRelayCoverageOk` est un
     * dernier résultat **mis en cache**, pas recalculé à l'ouverture — après une coupure réseau
     * (box éteinte, appareils qui redémarrent…), il peut rester bloqué sur « aucune couverture »
     * jusqu'à la prochaine resynchronisation réelle (Réglages, ou un appareil qui répond au
     * Tableau), qui ne s'est peut-être pas encore produite au tout premier affichage. Sans ce
     * délai, l'utilisateur se prend le bandeau à chaque lancement le temps que ça se corrige tout
     * seul, même quand la situation réelle est déjà bonne.
     */
    private val graceElapsed = MutableStateFlow(false)

    /**
     * Vrai si au moins un détecteur de fumée est présent, ntfy activé, **et** qu'aucun appareil
     * ne peut actuellement relayer ses alertes (dernier résultat connu, voir
     * `AppPreferences.smokeRelayCoverageOk`) — jamais recalculé ici, seulement observé.
     */
    val showSmokeRelayBanner: StateFlow<Boolean> = combine(
        repository.observeDevices(),
        appPreferences.ntfyEnabled,
        appPreferences.smokeRelayCoverageOk,
        graceElapsed,
    ) { devices, ntfyEnabled, coverageOk, grace ->
        grace && ntfyEnabled && !coverageOk && devices.any { it.type == DeviceType.SMOKE_DETECTOR }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Mode démo actif (retour David, 2026-09-21) : `HestiaApp` force alors la locale anglaise de
     * toute l'appli, indépendamment de la langue du téléphone — les captures F-Droid doivent
     * toutes être dans la même langue, jamais un mélange avec la langue système utilisée pour les
     * prendre.
     */
    val demoModeActive: StateFlow<Boolean> = repository.observeDevices()
        .map { repository.isDemoDeviceList(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            delay(GRACE_PERIOD_MS)
            graceElapsed.value = true
        }
    }

    /** Appelé au clic sur le bandeau, juste avant de naviguer vers Réglages. */
    fun onSmokeRelayBannerClicked() {
        scrollCoordinator.requestScrollToNtfy()
    }

    private companion object {
        /** Le temps qu'un premier relevé (Tableau ou Réglages) ait une chance de corriger un
         * état mis en cache avant la coupure — voir [graceElapsed]. */
        const val GRACE_PERIOD_MS = 15_000L
    }
}
