package kapoue.hestia.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kapoue.hestia.R
import kapoue.hestia.core.util.formatPower
import kapoue.hestia.ui.theme.stateColors

/**
 * Tuile d'un canal volet (2026-10-07). Même gabarit que [LightTile] ; état toujours écrit en
 * texte (jamais la seule couleur, voir CLAUDE.md). Trois boutons ▲ ■ ▼ directement sur la tuile ;
 * le tap sur la tuile ouvre le Détail.
 */
@Composable
fun CoverTile(
    tile: TileUiState,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenFirmware: () -> Unit,
) {
    val colors = MaterialTheme.stateColors
    val status = tile.coverStatus ?: CoverStatus.Loading
    val online = status as? CoverStatus.Online
    // Actif si le volet est (partiellement) ouvert ou en mouvement ; au repos seulement s'il est fermé.
    val active = online != null && (
        online.motion == CoverMotion.OPENING || online.motion == CoverMotion.CLOSING ||
            online.motion == CoverMotion.CALIBRATING || (online.position ?: 0) > 0 ||
            online.motion == CoverMotion.OPEN
        )
    val (bg, fg) = when {
        active -> colors.activeBg to colors.activeText
        online != null -> colors.idleBg to colors.idleText
        status is CoverStatus.Offline -> colors.offlineBg to colors.offlineText
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Recalculé chaque minute même si rien d'autre ne recompose la tuile.
    val now by produceState(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)) {
        while (true) {
            delay(60_000L)
            value = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        }
    }
    val next = nextCoverEvent(tile.coverEvents, now)
    val label = coverStateLabel(status, next)
    Surface(color = bg, shape = RoundedCornerShape(12.dp), onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tile.device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                Icons.Filled.Blinds,
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            online?.powerWatts?.takeIf { tile.device.hasPowerMetering }?.let {
                Text(
                    text = formatPower(it),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onOpen, enabled = online != null && online.motion != CoverMotion.CALIBRATING) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.cover_open), tint = fg)
                }
                IconButton(onClick = onStop, enabled = online != null) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.cover_stop), tint = fg)
                }
                IconButton(onClick = onClose, enabled = online != null && online.motion != CoverMotion.CALIBRATING) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.cover_close), tint = fg)
                }
            }
            if (tile.device.firmwareUpdateAvailable) {
                FirmwareUpdateBanner(onClick = onOpenFirmware)
            }
        }
    }
}
