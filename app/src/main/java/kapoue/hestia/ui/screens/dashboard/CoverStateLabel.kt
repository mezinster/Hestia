package kapoue.hestia.ui.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.time.format.TextStyle
import kapoue.hestia.R
import kapoue.hestia.core.util.formatClockTime
import kapoue.hestia.core.util.formatDate
import kapoue.hestia.domain.model.CoverEventAction

/**
 * Libellé d'état d'un volet, toujours écrit (jamais la seule couleur, voir CLAUDE.md). La position
 * n'est ajoutée que lorsqu'elle est connue (volet calibré).
 */
@Composable
internal fun coverStateLabel(status: CoverStatus, next: NextCoverEvent? = null): String {
    val state = coverPlainStateLabel(status)
    if (next == null || !coverShowsNext(status)) return state
    val time = formatClockTime(next.at.hour, next.at.minute)
    // Unique à plus de 6 jours : date courte (comme dans la liste des événements), sinon nom du jour.
    val day = when (coverNextDay(next)) {
        CoverNextDay.DATE -> formatDate(next.at.toLocalDate())
        else -> next.at.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0])
    }
    val nextText = when (val a = next.action) {
        is CoverEventAction.GoTo ->
            if (next.today) stringResource(coverNextStringRes(next), a.position, time)
            else stringResource(coverNextStringRes(next), a.position, day, time)
        else ->
            if (next.today) stringResource(coverNextStringRes(next), time)
            else stringResource(coverNextStringRes(next), day, time)
    }
    return stringResource(R.string.cover_state_with_next, state, nextText)
}

@Composable
private fun coverPlainStateLabel(status: CoverStatus): String = when (status) {
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
