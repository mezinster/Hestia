package kapoue.hestia.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kapoue.hestia.R
import kapoue.hestia.data.repository.CoverEventResult
import kapoue.hestia.data.rpc.ScheduleCodec
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import kapoue.hestia.ui.components.TimeWheelPicker
import kotlin.math.roundToInt

/** Jours cron dans l'ordre d'affichage (lundi d'abord, dimanche = 0 en dernier), comme les plannings relais. */
private val COVER_WEEK_DAYS = listOf(1, 2, 3, 4, 5, 6, 0)

private fun cronDayName(cronDay: Int): String =
    DayOfWeek.of(if (cronDay == 0) 7 else cronDay).getDisplayName(TextStyle.SHORT, Locale.getDefault())

/**
 * Dialogue d'ajout d'un événement ([window] faux), d'une plage ([window] vrai) ou de modification
 * d'un événement existant ([initial] renseigné). Se ferme sur succès (voir l'écran) ; les refus
 * (date passée, doublon, limite, erreur) s'affichent ici.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddCoverEventDialog(
    initial: CoverEvent?,
    window: Boolean,
    canPosition: Boolean,
    result: CoverEventResult?,
    onSaveEvent: (CoverEvent) -> Unit,
    onSaveWindow: (CoverEvent, CoverEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialChoice = initial?.let { coverDaysChoiceOf(it) } ?: CoverDaysChoice.EveryDay
    var hour by remember { mutableStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    // Plage : premier et second sélecteur (« Ouvrir à » puis « Fermer à », ou l'inverse si inversée).
    var secondHour by remember { mutableStateOf(21) }
    var secondMinute by remember { mutableStateOf(0) }
    var inverted by remember { mutableStateOf(false) }

    var everyDay by remember { mutableStateOf(initialChoice is CoverDaysChoice.EveryDay) }
    var days by remember { mutableStateOf((initialChoice as? CoverDaysChoice.Days)?.days ?: emptySet()) }
    var once by remember { mutableStateOf(initialChoice is CoverDaysChoice.Once) }
    var onceDate by remember { mutableStateOf((initialChoice as? CoverDaysChoice.Once)?.date ?: LocalDate.now()) }

    var action by remember {
        mutableStateOf(
            when (initial?.action) {
                CoverEventAction.Close -> CoverActionChoice.Close
                is CoverEventAction.GoTo -> CoverActionChoice.Position
                else -> CoverActionChoice.Open
            },
        )
    }
    var position by remember { mutableFloatStateOf(((initial?.action as? CoverEventAction.GoTo)?.position ?: 50).toFloat()) }

    val whenChoice: CoverDaysChoice = when {
        once -> CoverDaysChoice.Once(onceDate)
        everyDay -> CoverDaysChoice.EveryDay
        else -> CoverDaysChoice.Days(days)
    }
    val valid = whenChoice.isValid

    // Empêche un double envoi pendant l'aller-retour réseau ; repasse à faux dès qu'un résultat arrive.
    var submitting by remember { mutableStateOf(false) }
    // Le refus affiché disparaît (et le bouton se réactive) dès que l'utilisateur change une valeur.
    var showResult by remember { mutableStateOf(true) }
    LaunchedEffect(result) { if (result != null) { submitting = false; showResult = true } }
    LaunchedEffect(hour, minute, secondHour, secondMinute, inverted, everyDay, days, once, onceDate, action, position) {
        submitting = false
        showResult = false
    }

    val resultMessage = result?.takeIf { showResult }?.let(::coverEventResultMessage)?.let { stringResource(it) }
    val errorText = resultMessage ?: if (!valid) stringResource(R.string.planning_error_no_day) else null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        initial != null -> R.string.cover_event_edit_title
                        window -> R.string.cover_window_dialog_title
                        else -> R.string.cover_event_dialog_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )

            if (window) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(if (inverted) R.string.cover_window_close_at else R.string.cover_window_open_at),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TimeWheelPicker(hour, minute) { h, m -> hour = h; minute = m }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(if (inverted) R.string.cover_window_open_at else R.string.cover_window_close_at),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TimeWheelPicker(secondHour, secondMinute) { h, m -> secondHour = h; secondMinute = m }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = inverted, onCheckedChange = { inverted = it })
                    Text(stringResource(R.string.cover_window_inverted), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    TimeWheelPicker(hour, minute) { h, m -> hour = h; minute = m }
                }
                Text(stringResource(R.string.cover_event_action), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = action == CoverActionChoice.Open,
                        onClick = { action = CoverActionChoice.Open },
                        label = { Text(stringResource(R.string.cover_open)) },
                    )
                    FilterChip(
                        selected = action == CoverActionChoice.Close,
                        onClick = { action = CoverActionChoice.Close },
                        label = { Text(stringResource(R.string.cover_close)) },
                    )
                    FilterChip(
                        selected = action == CoverActionChoice.Position,
                        onClick = { action = CoverActionChoice.Position },
                        enabled = canPosition,
                        label = { Text(stringResource(R.string.cover_action_position)) },
                    )
                }
                if (!canPosition) {
                    Text(
                        stringResource(R.string.cover_event_position_needs_calibration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (action == CoverActionChoice.Position) {
                    Text(
                        stringResource(R.string.cover_position, position.roundToInt().coerceIn(0, 100)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(value = position, onValueChange = { position = it }, valueRange = 0f..100f)
                }
            }

            // Une plage et un événement partagent le même choix de jours / « Une fois ».
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.cover_event_once),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = once,
                    onCheckedChange = { checked ->
                        once = checked
                        if (checked) {
                            everyDay = false
                        } else if (!everyDay && days.isEmpty()) {
                            // Retour au récurrent sans sélection restante : « Tous les jours » par défaut.
                            everyDay = true
                        }
                    },
                )
            }

            if (once) {
                Text(stringResource(R.string.planning_once_day), style = MaterialTheme.typography.bodyMedium)
                // Aujourd'hui puis les sept prochains jours (même principe que les plannings relais).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = onceDate == LocalDate.now(),
                        onClick = { onceDate = LocalDate.now() },
                        label = { Text(stringResource(R.string.planning_today)) },
                    )
                    COVER_WEEK_DAYS.forEach { d ->
                        val occurrence = ScheduleCodec.nextOccurrence(d)
                        FilterChip(
                            selected = onceDate == occurrence,
                            onClick = { onceDate = occurrence },
                            label = { Text(cronDayName(d)) },
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.planning_days), style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = everyDay,
                    onClick = { everyDay = true; days = emptySet() },
                    label = { Text(stringResource(R.string.planning_all_days)) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    COVER_WEEK_DAYS.forEach { d ->
                        FilterChip(
                            selected = !everyDay && d in days,
                            onClick = {
                                everyDay = false
                                days = if (d in days) days - d else days + d
                            },
                            label = { Text(cronDayName(d)) },
                        )
                    }
                }
            }

            errorText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Button(
                enabled = valid && !submitting && canSaveCoverEvent(action, canPosition),
                onClick = {
                    submitting = true
                    if (window) {
                        val (first, second) = buildCoverWindow(hour, minute, secondHour, secondMinute, whenChoice, inverted)
                        onSaveWindow(first, second)
                    } else {
                        onSaveEvent(buildCoverEvent(hour, minute, whenChoice, action, position.roundToInt()))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (submitting) Icons.Filled.Check else Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(if (submitting) R.string.planning_validate_done else R.string.planning_validate))
            }
        }
    }
}
