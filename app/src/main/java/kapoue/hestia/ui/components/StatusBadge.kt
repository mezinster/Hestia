package kapoue.hestia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kapoue.hestia.ui.theme.stateColors

private enum class BadgeLed { FILLED, HOLLOW, SPINNER }

/**
 * Badge d'état d'un canal (LED + libellé texte), cohérent avec les tuiles du Tableau.
 * La couleur seule ne porte jamais l'information : le libellé l'accompagne toujours.
 */
@Composable
fun StatusBadge(
    status: TileStatus,
    elapsedNow: Long,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.stateColors
    val online = status as? TileStatus.Online
    val remaining = online?.timerEndsAtElapsed?.let { ((it - elapsedNow) / 1000).coerceAtLeast(0) }

    val led: BadgeLed
    val color: Color
    val labelRes: Int
    when {
        status is TileStatus.Loading -> {
            led = BadgeLed.SPINNER; color = colors.idleText; labelRes = R.string.state_loading
        }
        remaining != null && remaining > 0 -> {
            led = BadgeLed.FILLED; color = colors.timedLed; labelRes = R.string.state_timed
        }
        online != null && online.output -> {
            led = BadgeLed.FILLED; color = colors.activeLed; labelRes = R.string.state_active
        }
        online != null -> {
            led = BadgeLed.HOLLOW; color = colors.idleLed; labelRes = R.string.state_idle
        }
        else -> {
            led = BadgeLed.FILLED; color = colors.offlineLed; labelRes = R.string.state_offline
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        when (led) {
            BadgeLed.FILLED -> Box10(Modifier.clip(RoundedCornerShape(50)).background(color))
            BadgeLed.HOLLOW -> Box10(Modifier.border(1.5.dp, color, RoundedCornerShape(50)))
            BadgeLed.SPINNER -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = if (led == BadgeLed.SPINNER) MaterialTheme.colorScheme.onSurfaceVariant else color,
        )
    }
}

@Composable
private fun Box10(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier = Modifier.size(12.dp).then(modifier))
}
