package kapoue.hestia.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kapoue.hestia.R

/**
 * Sélecteur de durée « Perso » en bottom sheet (version simple : deux champs heures/minutes).
 * La métaphore des rouleaux crantés viendra en finition ; ici on valide le fonctionnement.
 * Durée minimale : 1 minute.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationPickerSheet(
    onDismiss: () -> Unit,
    onConfirm: (seconds: Int, label: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var hoursText by remember { mutableStateOf("0") }
    var minutesText by remember { mutableStateOf("30") }
    var showError by remember { mutableStateOf(false) }

    val hours = hoursText.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val minutes = minutesText.toIntOrNull()?.coerceIn(0, 59) ?: 0
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it.filter(Char::isDigit).take(2); showError = false },
                    label = { Text(stringResource(R.string.duration_picker_hours)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it.filter(Char::isDigit).take(2); showError = false },
                    label = { Text(stringResource(R.string.duration_picker_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            // Durée résultante affichée en monospace.
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            if (showError) {
                Text(
                    text = stringResource(R.string.duration_picker_min_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    if (totalSeconds < 60) showError = true else onConfirm(totalSeconds, label)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.duration_picker_start))
            }
        }
    }
}
