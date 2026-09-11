package kapoue.hestia.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kapoue.hestia.R
import kapoue.hestia.ui.components.TimeWheelPicker
import kapoue.hestia.ui.components.ValueWheelPicker

/**
 * Sélecteur de durée en bottom sheet : molette heures/minutes, coupure sur seuil optionnelle, et
 * « sans limite de durée ». Les deux sont indépendants : durée et seuil peuvent être décochés
 * ensemble (retour David, 2026-09-11) — la prise s'allume alors sans aucune limite automatique,
 * ni durée ni coupure, comme un simple allumage depuis la tuile (mais accessible en un tap
 * nommé). Réutilisé pour quatre usages distincts (titre/bouton/valeurs initiales fournis par
 * l'appelant) : le minuteur « Manuel » (démarre tout de suite), les deux réglages « Perso »
 * enregistrables (sauvegarde sans rien envoyer à la prise, nommés), et le minuteur du bouton
 * physique. Durée minimale : 1 minute (non applicable en mode sans limite).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationPickerSheet(
    hasPowerMetering: Boolean,
    title: String,
    confirmLabel: String,
    confirmIcon: ImageVector? = null,
    initialHours: Int = 0,
    initialMinutes: Int = 30,
    initialCutoffEnabled: Boolean = false,
    initialThresholdW: Int = DEFAULT_THRESHOLD_W,
    initialUnlimited: Boolean = false,
    showNameField: Boolean = false,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (seconds: Int?, label: String, thresholdW: Int?, name: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var hours by remember { mutableStateOf(initialHours) }
    var minutes by remember { mutableStateOf(initialMinutes) }
    var showError by remember { mutableStateOf(false) }
    var showNameError by remember { mutableStateOf(false) }
    var cutoffEnabled by remember { mutableStateOf(initialCutoffEnabled) }
    var threshold by remember { mutableStateOf(initialThresholdW) }
    var name by remember { mutableStateOf(initialName) }
    var unlimited by remember { mutableStateOf(initialUnlimited) }

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
            Text(text = title, style = MaterialTheme.typography.titleMedium)

            if (showNameField) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= MAX_NAME_LENGTH) { name = it; showNameError = false } },
                    label = { Text(stringResource(R.string.timer_preset_name_label)) },
                    isError = showNameError,
                    supportingText = if (showNameError) {
                        { Text(stringResource(R.string.timer_preset_name_error)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Sans limite de durée : indépendant de la coupure sur seuil (voir switch suivant) —
            // les deux peuvent être décochés ensemble, la prise s'allume alors sans aucune limite
            // automatique (retour David, 2026-09-11).
            if (hasPowerMetering) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.duration_picker_unlimited_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = unlimited,
                        onCheckedChange = { unlimited = it; showError = false },
                    )
                }
                // Sans ça, le seuil configuré semble ne jamais se déclencher pendant les 15
                // premières minutes — vécu en test réel, pris pour un bug (retour David, 2026-08-22).
                // Uniquement pertinente si la coupure sur seuil est vraiment active.
                if (unlimited && cutoffEnabled) {
                    Text(
                        text = stringResource(R.string.duration_picker_unlimited_grace_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!unlimited) {
                // Molette heures/minutes (00–23 / 00–59), lue comme une durée.
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TimeWheelPicker(hours, minutes) { h, m -> hours = h; minutes = m; showError = false }
                }
            }

            // Coupure sur seuil de consommation (uniquement si la prise mesure la puissance).
            // Librement combinable avec « sans limite de durée » ci-dessus, décoché y compris :
            // la prise s'allume alors sans aucune coupure automatique, comme la bascule de la
            // tuile (retour David, 2026-09-11 — jusque-là imposée en mode sans limite).
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
                    Switch(
                        checked = cutoffEnabled,
                        onCheckedChange = { cutoffEnabled = it },
                    )
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
                    when {
                        !unlimited && totalSeconds < 60 -> showError = true
                        showNameField && name.isBlank() -> showNameError = true
                        else -> onConfirm(
                            if (unlimited) null else totalSeconds,
                            if (unlimited) "" else label,
                            if (cutoffEnabled) threshold else null,
                            name.trim(),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (confirmIcon != null) {
                    Icon(confirmIcon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                }
                Text(confirmLabel)
            }
        }
    }
}

private val CUTOFF_THRESHOLDS_W = listOf(5, 10, 20, 30, 40, 50)
private const val DEFAULT_THRESHOLD_W = 10
private const val MAX_NAME_LENGTH = 26
