package kapoue.hestia.ui.screens.dashboard

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kapoue.hestia.core.util.formatPower
import kapoue.hestia.ui.theme.stateColors

/**
 * Bandeau multi-canaux d'un variateur en mode « light » (2026-10-07) : un contrôleur RGBW expose
 * jusqu'à 4 canaux sur une même adresse, affichés comme les blocs de relais (une ligne de petits
 * cercles) plutôt qu'une grande tuile chacun. Pas de bouton ON/OFF dans les cercles : le tap
 * ouvre le Détail du canal (curseur de luminosité, minuteur) — la modale rapide est propre aux
 * relais. Pas de pastille de connectivité non plus (propre aux relais) : le texte d'état de
 * chaque cercle dit déjà « Indisponible ».
 */
@Composable
fun LightStripRow(
    groupLabel: String,
    members: List<TileUiState>,
    /** Horloge rafraîchie à la seconde par le parent : décompte du minuteur. */
    elapsedNow: Long,
    onOpenChannel: (TileUiState) -> Unit,
    onOpenFirmware: (Long) -> Unit,
) {
    val colors = MaterialTheme.stateColors
    Surface(
        color = colors.idleBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = groupLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    members.forEach { member ->
                        MiniLightCircle(
                            tile = member,
                            elapsedNow = elapsedNow,
                            onClick = { onOpenChannel(member) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            // Firmware partagé par tous les canaux d'un même appareil : un seul bandeau.
            if (members.any { it.device.firmwareUpdateAvailable }) {
                FirmwareUpdateBanner(onClick = { onOpenFirmware(members.first().device.id) })
            }
        }
    }
}

@Composable
private fun MiniLightCircle(tile: TileUiState, elapsedNow: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.stateColors
    val status = tile.lightStatus ?: LightStatus.Loading
    val online = status as? LightStatus.Online
    // Mêmes couleurs que LightTile.
    val (bg, fg) = when {
        online?.on == true -> colors.activeBg to colors.activeText
        online != null -> colors.idleBg to colors.idleText
        status is LightStatus.Offline -> colors.offlineBg to colors.offlineText
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Toujours rendu, même vide : garde la même hauteur pour tous les canaux de la ligne.
    val powerWatts = online?.powerWatts?.takeIf { tile.device.hasPowerMetering }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Surface(color = bg, shape = RoundedCornerShape(50), modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.padding(10.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = tile.device.name.take(STRIP_NAME_MAX_LENGTH),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = lightStateLabel(status, elapsedNow),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            // 3 lignes réservées : dans un cercle étroit, « Allumée · 40 % · extinction dans
            // 0:29:12 » ne tient pas sur 2 lignes et le décompte serait coupé.
            minLines = 3,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = powerWatts?.let { formatPower(it) }.orEmpty(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
