package kapoue.hestia.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.ui.screens.dashboard.LightStatus
import kapoue.hestia.ui.screens.dashboard.lightStateLabel
import kotlin.math.roundToInt

/**
 * Section Détail d'un variateur (2026-10-07) : état, marche/arrêt, luminosité. Le curseur ne
 * déplace que l'affichage pendant le glissé et n'envoie qu'au relâchement — jamais un Light.Set
 * par pas de glissé. Après chaque relevé (ou un échec), il se recale sur la valeur réelle.
 */
@Composable
fun LightSection(
    status: LightStatus,
    showPower: Boolean,
    revision: Int,
    /** Horloge rafraîchie à la seconde par l'écran : décompte du minuteur (C1). */
    elapsedNow: Long,
    onSet: (on: Boolean?, brightness: Int?) -> Unit,
    /** Allume pour [seconds] secondes : l'appareil s'éteint seul ensuite (`toggle_after`). */
    onStartTimer: (seconds: Int) -> Unit,
    /** Planning précis en cours (hors présence, hors désactivé aujourd'hui) : fin affichée dans l'état (C3). */
    activePlanning: Planning? = null,
) {
    val online = status as? LightStatus.Online
    var sliderValue by remember { mutableFloatStateOf(online?.brightness?.toFloat() ?: 100f) }
    var dragging by remember { mutableStateOf(false) }
    // Clé = luminosité lue (pas tout le statut : la puissance bouge à chaque relevé) + révision,
    // incrémentée après chaque commande pour recaler aussi quand l'état relu est identique (échec).
    LaunchedEffect(online?.brightness, revision) { resyncTarget(status, dragging)?.let { sliderValue = it } }

    var showCustomTimer by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = lightStateLabel(status, elapsedNow, activePlanning), style = MaterialTheme.typography.titleMedium)
        if (showPower) online?.powerWatts?.let { Text(formatPower(it), style = MaterialTheme.typography.bodyMedium) }
        if (online?.on == true) {
            OutlinedButton(onClick = { onSet(false, null) }) { Text(stringResource(R.string.light_turn_off)) }
        } else {
            Button(onClick = { onSet(true, null) }, enabled = online != null) { Text(stringResource(R.string.light_turn_on)) }
        }
        Text(stringResource(R.string.light_brightness, sliderValue.roundToInt().coerceIn(1, 100)), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = sliderValue,
            onValueChange = { dragging = true; sliderValue = it },
            onValueChangeFinished = { dragging = false; sliderCommand(sliderValue).let { onSet(it.on, it.brightness) } },
            valueRange = 1f..100f,
            enabled = online != null,
        )
        // Minuteur (C1, 2026-10-07) : tenu par l'appareil, il s'exécute même téléphone éteint ;
        // l'annuler = éteindre, comme pour un relais (pas de bouton dédié).
        Text(stringResource(R.string.light_timer_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LIGHT_TIMER_PRESETS_SEC.forEach { seconds ->
                AssistChip(onClick = { onStartTimer(seconds) }, label = { Text(durationLabel(seconds)) }, enabled = online != null)
            }
            AssistChip(
                onClick = { showCustomTimer = true },
                label = { Text(stringResource(R.string.detail_timer_custom)) },
                enabled = online != null,
            )
        }
    }

    if (showCustomTimer) {
        DurationPickerSheet(
            hasPowerMetering = false,
            title = stringResource(R.string.duration_picker_title),
            confirmLabel = stringResource(R.string.duration_picker_start),
            onDismiss = { showCustomTimer = false },
            onConfirm = { seconds, _, _, _ ->
                showCustomTimer = false
                // « Sans limite » : simple allumage, sans minuteur.
                if (seconds != null) onStartTimer(seconds) else onSet(true, null)
            },
        )
    }
}

/** Durées proposées en un tap : 30 min, 1 h, 2 h. */
private val LIGHT_TIMER_PRESETS_SEC = listOf(1800, 3600, 7200)
