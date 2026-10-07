package kapoue.hestia.ui.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kapoue.hestia.R

/**
 * Libellé d'état d'un volet, toujours écrit (jamais la seule couleur, voir CLAUDE.md). La position
 * n'est ajoutée que lorsqu'elle est connue (volet calibré).
 */
@Composable
fun coverStateLabel(status: CoverStatus): String = when (status) {
    CoverStatus.Loading -> stringResource(R.string.state_loading)
    CoverStatus.Offline -> stringResource(R.string.state_offline)
    is CoverStatus.Online -> {
        val pos = status.position
        when (status.motion) {
            CoverMotion.OPEN -> stringResource(R.string.cover_state_open)
            CoverMotion.CLOSED -> stringResource(R.string.cover_state_closed)
            CoverMotion.OPENING ->
                if (pos != null) stringResource(R.string.cover_state_opening_at, pos) else stringResource(R.string.cover_state_opening)
            CoverMotion.CLOSING ->
                if (pos != null) stringResource(R.string.cover_state_closing_at, pos) else stringResource(R.string.cover_state_closing)
            CoverMotion.STOPPED ->
                if (pos != null) stringResource(R.string.cover_state_stopped_at, pos) else stringResource(R.string.cover_state_stopped)
            CoverMotion.CALIBRATING -> stringResource(R.string.cover_state_calibrating)
            CoverMotion.UNKNOWN -> stringResource(R.string.cover_state_stopped)
        }
    }
}
