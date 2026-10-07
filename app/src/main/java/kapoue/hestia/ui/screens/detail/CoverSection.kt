package kapoue.hestia.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.core.util.formatPower
import kapoue.hestia.ui.screens.dashboard.CoverFault
import kapoue.hestia.ui.screens.dashboard.CoverStatus
import kapoue.hestia.ui.screens.dashboard.coverStateLabel
import kapoue.hestia.ui.theme.stateColors
import kotlin.math.roundToInt

/**
 * Section Détail d'un volet (2026-10-07) : état, commandes Ouvrir/Arrêter/Fermer, position et
 * calibration. Même mécanique de curseur que [LightSection] : pas de recalage pendant le glissé,
 * envoi au seul relâchement, recalage sur la position lue (+ révision) ensuite.
 */
@Composable
fun CoverSection(
    status: CoverStatus,
    showPower: Boolean,
    revision: Int,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onGoTo: (pos: Int) -> Unit,
    onCalibrate: () -> Unit,
) {
    val online = status as? CoverStatus.Online
    var sliderValue by remember { mutableFloatStateOf(online?.position?.toFloat() ?: 0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(online?.position, revision) { coverResyncTarget(status, dragging)?.let { sliderValue = it } }

    var showCalibrate by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = coverStateLabel(status), style = MaterialTheme.typography.titleMedium)
        if (showPower) online?.powerWatts?.let { Text(formatPower(it), style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen, enabled = online != null) { Text(stringResource(R.string.cover_open)) }
            OutlinedButton(onClick = onStop, enabled = online != null) { Text(stringResource(R.string.cover_stop)) }
            Button(onClick = onClose, enabled = online != null) { Text(stringResource(R.string.cover_close)) }
        }
        if (online?.positionControl == true) {
            Text(
                stringResource(R.string.cover_position, sliderValue.roundToInt().coerceIn(0, 100)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = sliderValue,
                onValueChange = { dragging = true; sliderValue = it },
                onValueChangeFinished = { dragging = false; onGoTo(coverSliderTarget(sliderValue)) },
                valueRange = 0f..100f,
            )
        } else if (online != null) {
            Text(stringResource(R.string.cover_needs_calibration), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = { showCalibrate = true }, enabled = online != null) {
            Text(stringResource(R.string.cover_calibrate))
        }
        online?.faults?.forEach { fault ->
            Text(
                text = stringResource(coverFaultRes(fault)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.stateColors.warningText,
            )
        }
    }

    if (showCalibrate) {
        AlertDialog(
            onDismissRequest = { showCalibrate = false },
            title = { Text(stringResource(R.string.cover_calibrate_title)) },
            text = { Text(stringResource(R.string.cover_calibrate_message)) },
            confirmButton = {
                TextButton(onClick = { showCalibrate = false; onCalibrate() }) {
                    Text(stringResource(R.string.cover_calibrate))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalibrate = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun coverFaultRes(fault: CoverFault): Int = when (fault) {
    CoverFault.OBSTRUCTION -> R.string.cover_fault_obstruction
    CoverFault.OVERPOWER -> R.string.cover_fault_overpower
    CoverFault.OVERTEMP -> R.string.cover_fault_overtemp
    CoverFault.VOLTAGE -> R.string.cover_fault_voltage
    CoverFault.SAFETY_SWITCH -> R.string.cover_fault_safety_switch
    CoverFault.OTHER -> R.string.cover_fault_generic
}
