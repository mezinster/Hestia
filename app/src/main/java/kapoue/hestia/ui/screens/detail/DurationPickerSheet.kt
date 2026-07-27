package kapoue.hestia.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.ui.components.TimeWheelPicker
import kapoue.hestia.ui.components.ValueWheelPicker

/**
 * Sélecteur de durée « Perso » en bottom sheet : molette heures/minutes. La durée n'est plus
 * répétée en texte (on la lit dans la molette). Sur les prises qui mesurent la consommation, une
 * option de **coupure sur seuil** permet d'arrêter avant la fin si la conso reste basse.
 * Durée minimale : 1 minute.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationPickerSheet(
    hasPowerMetering: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (seconds: Int, label: String, thresholdW: Int?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var hours by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(30) }
    var showError by remember { mutableStateOf(false) }
    var cutoffEnabled by remember { mutableStateOf(false) }
    var threshold by remember { mutableStateOf(DEFAULT_THRESHOLD_W) }

    val totalSeconds = hours * 3600 + minutes * 60
    val label = durationLabel(totalSeconds)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.duration_picker_title),
                style = MaterialTheme.typography.titleMedium,
            )
            // Molette heures/minutes (00–23 / 00–59), lue comme une durée.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TimeWheelPicker(hours, minutes) { h, m -> hours = h; minutes = m; showError = false }
            }

            // Coupure sur seuil de consommation (uniquement si la prise mesure la puissance).
            if (hasPowerMetering) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.timer_cutoff_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = cutoffEnabled, onCheckedChange = { cutoffEnabled = it })
                }
                if (cutoffEnabled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ValueWheelPicker(values = CUTOFF_THRESHOLDS_W, value = threshold, onChange = { threshold = it })
                        Text(" Watts", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            if (showError) {
                Text(
                    text = stringResource(R.string.duration_picker_min_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    if (totalSeconds < 60) {
                        showError = true
                    } else {
                        onConfirm(totalSeconds, label, if (cutoffEnabled) threshold else null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.duration_picker_start))
            }
        }
    }
}

private val CUTOFF_THRESHOLDS_W = listOf(5, 10, 20, 30, 40, 50)
private const val DEFAULT_THRESHOLD_W = 10
