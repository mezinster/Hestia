package kapoue.hestia.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.core.util.formatCountdown
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.ui.components.BreakerSwitch
import kapoue.hestia.ui.theme.StateColorSet
import kapoue.hestia.ui.theme.stateColors

/**
 * Tuile d'un canal sur le Tableau. Métaphore « tableau électrique » : numéro monospace,
 * LED d'état **toujours doublée d'un libellé texte**, interrupteur rectangulaire.
 *
 * @param elapsedNow SystemClock.elapsedRealtime() courant, rafraîchi à la seconde par le parent
 *   pour décrémenter le compte à rebours localement.
 */
@Composable
fun DeviceTile(
    tile: TileUiState,
    elapsedNow: Long,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val colors = MaterialTheme.stateColors
    val visual = tile.status.toVisual(colors, elapsedNow)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(2.dp),
        onClick = onOpenDetail,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%02d".format(tile.number),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.size(8.dp))
                Icon(
                    imageVector = iconFor(tile.device.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = tile.device.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLed(style = visual.ledStyle, color = visual.ledColor)
                Spacer(Modifier.size(6.dp))
                Text(
                    text = visual.label,
                    color = visual.textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            visual.countdown?.let { countdown ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = countdown,
                    color = visual.textColor,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(10.dp))
            when (tile.status) {
                is TileStatus.Online, TileStatus.Loading -> {
                    val checked = (tile.status as? TileStatus.Online)?.output == true
                    BreakerSwitch(
                        checked = checked,
                        onCheckedChange = onToggle,
                        onColor = colors.activeLed,
                        enabled = tile.status is TileStatus.Online,
                    )
                }
                TileStatus.Offline -> {
                    TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                }
                TileStatus.PermissionRequired -> {
                    TextButton(onClick = onGrantPermission, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(stringResource(R.string.tile_grant_permission))
                    }
                }
            }
        }
    }
}

/** Rendu du voyant : disque plein (allumé), anneau creux (éteint) ou spinner (lecture). */
private enum class LedStyle { FILLED, HOLLOW, SPINNER }

private data class TileVisual(
    val ledColor: Color,
    val textColor: Color,
    val label: String,
    val countdown: String?,
    val ledStyle: LedStyle,
)

@Composable
private fun TileStatus.toVisual(colors: StateColorSet, elapsedNow: Long): TileVisual = when (this) {
    // Lecture en cours : spinner plutôt que gris (qui se lirait « désactivé »).
    TileStatus.Loading -> TileVisual(
        ledColor = colors.idleLed,
        textColor = colors.idleText,
        label = stringResource(R.string.state_loading),
        countdown = null,
        ledStyle = LedStyle.SPINNER,
    )
    is TileStatus.Online -> {
        val remaining = timerEndsAtElapsed?.let { ((it - elapsedNow) / 1000).coerceAtLeast(0) }
        when {
            remaining != null && remaining > 0 -> TileVisual(
                ledColor = colors.timedLed,
                textColor = colors.timedText,
                label = stringResource(R.string.state_timed),
                countdown = formatCountdown(remaining),
                ledStyle = LedStyle.FILLED,
            )
            output -> TileVisual(
                ledColor = colors.activeLed,
                textColor = colors.activeText,
                label = stringResource(R.string.state_active),
                countdown = null,
                ledStyle = LedStyle.FILLED,
            )
            // Repos : anneau creux neutre = « voyant éteint », jamais un disque gris « mort ».
            else -> TileVisual(
                ledColor = colors.idleLed,
                textColor = colors.idleText,
                label = stringResource(R.string.state_idle),
                countdown = null,
                ledStyle = LedStyle.HOLLOW,
            )
        }
    }
    TileStatus.Offline -> TileVisual(
        ledColor = colors.offlineLed,
        textColor = colors.offlineText,
        label = stringResource(R.string.state_offline),
        countdown = null,
        ledStyle = LedStyle.FILLED,
    )
    TileStatus.PermissionRequired -> TileVisual(
        ledColor = colors.offlineLed,
        textColor = colors.offlineText,
        label = stringResource(R.string.state_permission_required),
        countdown = null,
        ledStyle = LedStyle.FILLED,
    )
}

@Composable
private fun StatusLed(style: LedStyle, color: Color) {
    when (style) {
        LedStyle.FILLED -> Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        LedStyle.HOLLOW -> Box(
            modifier = Modifier
                .size(10.dp)
                .border(1.5.dp, color, RoundedCornerShape(50)),
        )
        LedStyle.SPINNER -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
        )
    }
}

private fun iconFor(type: DeviceType): ImageVector = when (type) {
    DeviceType.PLUG -> Icons.Filled.Power
    DeviceType.LAMP -> Icons.Filled.Lightbulb
    DeviceType.SENSOR -> Icons.Filled.Sensors
}
