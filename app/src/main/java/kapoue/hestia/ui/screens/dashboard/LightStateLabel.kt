package kapoue.hestia.ui.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kapoue.hestia.R
import kapoue.hestia.core.util.formatClockTime
import kapoue.hestia.core.util.formatCountdown
import kapoue.hestia.domain.model.Planning

/**
 * Libellé d'état d'un variateur, toujours écrit (jamais la seule couleur, voir CLAUDE.md) : partagé
 * entre la tuile du Tableau et l'écran Détail. Avec un minuteur en cours (C1, 2026-10-07), ajoute
 * le temps restant avant l'extinction ; sinon, avec un planning en cours ([activePlanning], C3),
 * l'heure de fin du planning.
 */
@Composable
fun lightStateLabel(status: LightStatus, elapsedNow: Long, activePlanning: Planning? = null): String {
    val online = status as? LightStatus.Online
    return when {
        online?.on == true -> when (val detail = lightOnDetail(lightTimerRemainingSec(online.timerEndsAtElapsed, elapsedNow), activePlanning)) {
            is LightOnDetail.Timer -> stringResource(R.string.light_state_on_timer, online.brightness, formatCountdown(detail.remainingSec))
            is LightOnDetail.Planned -> stringResource(R.string.light_state_on_planned, online.brightness, formatClockTime(detail.endMinutes))
            LightOnDetail.None -> stringResource(R.string.light_state_on, online.brightness)
        }
        online != null -> stringResource(R.string.light_state_off)
        status is LightStatus.Offline -> stringResource(R.string.state_offline)
        else -> stringResource(R.string.state_loading)
    }
}
