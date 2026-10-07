package kapoue.hestia.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.core.util.formatPower
import kapoue.hestia.ui.theme.stateColors

/**
 * Tuile d'un canal variateur (2026-10-07). Même gabarit que [DeviceTile] ; état toujours écrit en
 * texte (jamais la seule couleur, voir CLAUDE.md). Pas de curseur ici (trop petit, réglage
 * accidentel au défilement) : la luminosité se règle dans le Détail, ouvert au tap.
 */
@Composable
fun LightTile(
    tile: TileUiState,
    /** Horloge rafraîchie à la seconde par le parent : décompte du minuteur (C1). */
    elapsedNow: Long,
    onToggle: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
    onOpenFirmware: () -> Unit,
) {
    val colors = MaterialTheme.stateColors
    val status = tile.lightStatus ?: LightStatus.Loading
    val online = status as? LightStatus.Online
    val (bg, fg) = when {
        online?.on == true -> colors.activeBg to colors.activeText
        online != null -> colors.idleBg to colors.idleText
        status is LightStatus.Offline -> colors.offlineBg to colors.offlineText
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = lightStateLabel(status, elapsedNow, activeLightPlanning(tile.plannings, tile.planningDisabledToday))
    Surface(color = bg, shape = RoundedCornerShape(12.dp), onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tile.device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
            )
            Text(
                text = label,
                color = fg,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                // 2 lignes : avec un minuteur, « Allumée · 40 % · extinction dans 0:29:12 » ne tient pas toujours.
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                Text(
                    text = online?.powerWatts?.takeIf { tile.device.hasPowerMetering }?.let { formatPower(it) }.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                RoundToggleButton(enabled = online != null, checked = online?.on == true, onClick = { onToggle(online?.on != true) })
            }
            if (tile.device.firmwareUpdateAvailable) {
                FirmwareUpdateBanner(onClick = onOpenFirmware)
            }
        }
    }
}
