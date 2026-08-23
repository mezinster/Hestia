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
import androidx.compose.ui.unit.dp
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.ui.screens.dashboard.PresenceInfo
import kapoue.hestia.ui.screens.dashboard.TileStatus
import kapoue.hestia.ui.screens.dashboard.toVisual
import kapoue.hestia.ui.theme.stateColors

/**
 * Badge d'état d'un canal (point + libellé texte), en haut de l'écran Détail — même
 * classification que les tuiles du Tableau ([TileStatus.toVisual], réutilisé tel quel) : Actif,
 * Présence, Planifié, Éteint, Indisponible ont chacun leur couleur, jamais mélangés (2026-08-22 —
 * avant ça, ce badge avait sa propre logique plus pauvre, ne distinguait ni Présence ni Planifié).
 * Même principe que le cercle des tuiles : l'**anneau** du point reflète le fait physique (courant
 * ou non), le **centre** reflète le régime (même couleur que le libellé) — lisible même sans lire
 * le texte (retour David, 2026-08-22). La couleur seule ne porte jamais l'information : le
 * libellé l'accompagne toujours. Le seuil (« Coupure à X W ») n'est volontairement pas repris
 * ici, déjà affiché plus bas sur l'écran (section minuteur) — ce badge ne montre que l'état
 * général.
 */
@Composable
fun StatusBadge(
    status: TileStatus,
    elapsedNow: Long,
    presence: PresenceInfo? = null,
    activePlanning: Planning? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.stateColors
    val visual = status.toVisual(
        colors, elapsedNow, presence, activePlanning,
        pendingThresholdW = null, onSinceElapsed = null,
        presenceDisabledToday = false, planningDisabledToday = false,
    )

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (visual.loading) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        } else {
            Box10(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(visual.textColor)
                    .border(1.5.dp, visual.ringColor, RoundedCornerShape(50)),
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = visual.label,
            style = MaterialTheme.typography.titleSmall,
            color = if (visual.loading) MaterialTheme.colorScheme.onSurfaceVariant else visual.textColor,
        )
    }
}

@Composable
private fun Box10(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier = Modifier.size(12.dp).then(modifier))
}
