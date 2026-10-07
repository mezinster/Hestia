package kapoue.hestia.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.format.TextStyle
import kapoue.hestia.R
import kapoue.hestia.core.util.formatClockTime
import kapoue.hestia.core.util.formatDate
import kapoue.hestia.data.local.entity.PausedCoverEvent
import kapoue.hestia.data.local.entity.toEvent
import kapoue.hestia.data.repository.CoverEventResult
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction

/** Nombre maximal d'événements par volet (une plage en compte deux), miroir de la limite du dépôt. */
private const val COVER_EVENT_LIMIT = 10

/**
 * État d'interface de la programmation d'un volet (dialogue ouvert, confirmations en attente),
 * hissé hors de la section pour que dialogues et confirmations soient rendus hors de la `Column`
 * de l'écran, comme ceux des plannings relais.
 */
internal class CoverEventUiState {
    /** Dialogue ouvert : ajout d'événement, ajout de plage ou modification ([editing]). */
    var showAdd by mutableStateOf(false)
    var showWindow by mutableStateOf(false)
    var editing by mutableStateOf<CoverEvent?>(null)
    var toDelete by mutableStateOf<CoverEvent?>(null)
    var toPause by mutableStateOf<CoverEvent?>(null)
    var pausedToDelete by mutableStateOf<PausedCoverEvent?>(null)
}

/** Section « Programmation » d'un volet : liste, événements en pause et boutons d'ajout. */
@Composable
internal fun CoverEventSection(
    events: List<CoverEvent>,
    paused: List<PausedCoverEvent>,
    onAdd: () -> Unit,
    onAddWindow: () -> Unit,
    onEdit: (CoverEvent) -> Unit,
    onPause: (CoverEvent) -> Unit,
    onDelete: (CoverEvent) -> Unit,
    onResume: (PausedCoverEvent) -> Unit,
    onDeletePaused: (PausedCoverEvent) -> Unit,
) {
    // Les lignes en pause illisibles (date corrompue) sont écartées plutôt que de planter l'écran.
    val pausedRows = remember(paused) {
        paused.mapNotNull { p -> runCatching { p.toEvent() }.getOrNull()?.let { p to it } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.cover_events_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        events.forEach { e ->
            CoverEventRow(e, onEdit = { onEdit(e) }, onPause = { onPause(e) }, onDelete = { onDelete(e) })
        }
        OutlinedButton(onClick = onAdd, enabled = events.size < COVER_EVENT_LIMIT) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.cover_event_add))
        }
        // Une plage crée deux événements : elle exige deux places libres.
        OutlinedButton(onClick = onAddWindow, enabled = events.size + 2 <= COVER_EVENT_LIMIT) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.cover_event_add_window))
        }
        if (events.size >= COVER_EVENT_LIMIT - 1) {
            Text(
                text = stringResource(R.string.cover_event_error_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pausedRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.cover_event_paused),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pausedRows.forEach { (p, e) ->
                CoverEventRow(e, paused = true, onResume = { onResume(p) }, onDelete = { onDeletePaused(p) })
            }
        }
    }
}

@Composable
private fun CoverEventRow(
    event: CoverEvent,
    paused: Boolean = false,
    onEdit: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onDelete: () -> Unit,
) {
    val color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.cover_event_row,
                formatClockTime(event.hour, event.minute),
                coverDaysText(event),
                coverActionText(event.action),
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier
                .weight(1f)
                // Un tap sur la ligne ouvre l'édition ; un événement en pause n'est pas modifiable.
                .then(if (paused) Modifier else Modifier.clickable(onClick = onEdit)),
        )
        if (paused) {
            IconButton(onClick = onResume) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.cover_event_resume))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.planning_paused_delete))
            }
        } else {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cover_event_edit))
            }
            IconButton(onClick = onPause) {
                Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.planning_pause))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.planning_delete))
            }
        }
    }
}

/** Jours d'un événement : « Tous les jours », noms courts du jour (lundi d'abord) ou date courte. */
@Composable
private fun coverDaysText(event: CoverEvent): String = when (val label = coverDaysLabel(event)) {
    CoverDaysLabel.EveryDay -> stringResource(R.string.cover_event_every_day)
    is CoverDaysLabel.OnDate -> formatDate(label.date)
    is CoverDaysLabel.Days -> {
        val resources = LocalResources.current
        val locale = resources.configuration.locales[0]
        // Séparateur fourni par la ressource cover_event_days_list (pas de concaténation en dur).
        label.days
            .map { it.getDisplayName(TextStyle.SHORT, locale) }
            .reduce { acc, next -> resources.getString(R.string.cover_event_days_list, acc, next) }
    }
}

@Composable
private fun coverActionText(action: CoverEventAction): String = when (action) {
    CoverEventAction.Open -> stringResource(R.string.cover_open)
    CoverEventAction.Close -> stringResource(R.string.cover_close)
    is CoverEventAction.GoTo -> stringResource(R.string.cover_event_goto, action.position)
}

/**
 * Dialogues et confirmations de la programmation, rendus hors de la `Column` de l'écran.
 * [onSaveEvent] reçoit (ancien, nouveau) : l'ancien est nul pour un ajout.
 */
@Composable
internal fun CoverEventDialogs(
    state: CoverEventUiState,
    result: CoverEventResult?,
    canPosition: Boolean,
    onSaveEvent: (old: CoverEvent?, new: CoverEvent) -> Unit,
    onSaveWindow: (first: CoverEvent, second: CoverEvent) -> Unit,
    onDismissDialog: () -> Unit,
    onDelete: (CoverEvent) -> Unit,
    onPause: (CoverEvent) -> Unit,
    onDeletePaused: (PausedCoverEvent) -> Unit,
) {
    if (state.showAdd || state.showWindow || state.editing != null) {
        AddCoverEventDialog(
            initial = state.editing,
            window = state.showWindow && state.editing == null,
            canPosition = canPosition,
            result = result,
            onSaveEvent = { e -> onSaveEvent(state.editing, e) },
            onSaveWindow = onSaveWindow,
            onDismiss = {
                state.showAdd = false
                state.showWindow = false
                state.editing = null
                onDismissDialog()
            },
        )
    }

    state.toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { state.toDelete = null },
            title = { Text(stringResource(R.string.cover_event_delete_title)) },
            text = { Text(stringResource(R.string.cover_event_delete_message)) },
            confirmButton = {
                TextButton(onClick = { state.toDelete = null; onDelete(e) }) {
                    Text(stringResource(R.string.planning_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.toDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    state.toPause?.let { e ->
        AlertDialog(
            onDismissRequest = { state.toPause = null },
            title = { Text(stringResource(R.string.cover_event_pause_title)) },
            text = { Text(stringResource(R.string.cover_event_pause_message)) },
            confirmButton = {
                TextButton(onClick = { state.toPause = null; onPause(e) }) {
                    Text(stringResource(R.string.planning_pause_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.toPause = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    state.pausedToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { state.pausedToDelete = null },
            title = { Text(stringResource(R.string.cover_event_forget_title)) },
            text = { Text(stringResource(R.string.cover_event_forget_message)) },
            confirmButton = {
                TextButton(onClick = { state.pausedToDelete = null; onDeletePaused(p) }) {
                    Text(stringResource(R.string.planning_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.pausedToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
