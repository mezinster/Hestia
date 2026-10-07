package kapoue.hestia.ui.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kapoue.hestia.R
import kapoue.hestia.core.util.formatCountdown

/**
 * Libellé d'état d'un variateur, toujours écrit (jamais la seule couleur, voir CLAUDE.md) : partagé
 * entre la tuile du Tableau et l'écran Détail. Avec un minuteur en cours (C1, 2026-10-07), ajoute
 * le temps restant avant l'extinction.
 */
@Composable
fun lightStateLabel(status: LightStatus, elapsedNow: Long): String {
    val online = status as? LightStatus.Online
    return when {
        online?.on == true -> lightTimerRemainingSec(online.timerEndsAtElapsed, elapsedNow)
            ?.let { stringResource(R.string.light_state_on_timer, online.brightness, formatCountdown(it)) }
            ?: stringResource(R.string.light_state_on, online.brightness)
        online != null -> stringResource(R.string.light_state_off)
        status is LightStatus.Offline -> stringResource(R.string.state_offline)
        else -> stringResource(R.string.state_loading)
    }
}
