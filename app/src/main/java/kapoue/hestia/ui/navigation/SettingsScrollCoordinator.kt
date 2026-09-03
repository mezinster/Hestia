package kapoue.hestia.ui.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordonne un besoin de défilement ponctuel vers une section précise de l'écran Réglages, sans
 * passer par un argument de navigation — utilisé par le bandeau de couverture zéro du relais
 * ntfy des détecteurs de fumée (Lot 4c, voir [kapoue.hestia.ui.HestiaApp]) : un clic navigue vers
 * Réglages **et** signale ici qu'il faut se positionner sur la section ntfy dès que l'écran est
 * composé. `extraBufferCapacity = 1` : l'événement survit même émis juste avant que l'écran
 * Réglages n'ait fini de s'abonner (course de navigation).
 */
@Singleton
class SettingsScrollCoordinator @Inject constructor() {
    private val _scrollToNtfy = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToNtfy: SharedFlow<Unit> = _scrollToNtfy.asSharedFlow()

    fun requestScrollToNtfy() {
        _scrollToNtfy.tryEmit(Unit)
    }
}
