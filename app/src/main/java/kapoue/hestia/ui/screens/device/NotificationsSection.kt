package kapoue.hestia.ui.screens.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.domain.model.NotifyKind
import kapoue.hestia.domain.model.allows

private fun NotifyKind.labelRes(): Int = when (this) {
    NotifyKind.PLANNING_START -> R.string.notify_kind_planning_start
    NotifyKind.PLANNING_END -> R.string.notify_kind_planning_end
    NotifyKind.CUTOFF -> R.string.notify_kind_cutoff
    NotifyKind.TIMER_END -> R.string.notify_kind_timer_end
    NotifyKind.PRESENCE -> R.string.notify_kind_presence
    NotifyKind.COVER_MOVES -> R.string.notify_kind_cover_moves
}

/**
 * Section « Notifications » de l'écran Modifier : un interrupteur par type de notification
 * applicable à l'appareil. Masquée si [kinds] est vide (détecteur de fumée). Couper un type ne
 * change jamais le comportement de l'appareil, seulement ce qu'il envoie.
 */
@Composable
fun NotificationsSection(
    kinds: List<NotifyKind>,
    mask: Int,
    pending: Boolean,
    globallyOff: Boolean,
    onToggle: (NotifyKind, Boolean) -> Unit,
) {
    if (kinds.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.notify_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.notify_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (globallyOff) {
            Text(
                text = stringResource(R.string.notify_globally_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        kinds.forEach { kind ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(kind.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = mask.allows(kind), onCheckedChange = { onToggle(kind, it) })
            }
        }
        if (pending) {
            Text(
                text = stringResource(R.string.notify_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
