package kapoue.hestia.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import kapoue.hestia.ui.screens.dashboard.LightStatus
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
    onSet: (on: Boolean?, brightness: Int?) -> Unit,
) {
    val online = status as? LightStatus.Online
    var sliderValue by remember { mutableFloatStateOf(online?.brightness?.toFloat() ?: 100f) }
    var dragging by remember { mutableStateOf(false) }
    // Clé = luminosité lue (pas tout le statut : la puissance bouge à chaque relevé) + révision,
    // incrémentée après chaque commande pour recaler aussi quand l'état relu est identique (échec).
    LaunchedEffect(online?.brightness, revision) { resyncTarget(status, dragging)?.let { sliderValue = it } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = when {
                online?.on == true -> stringResource(R.string.light_state_on, online.brightness)
                online != null -> stringResource(R.string.light_state_off)
                status is LightStatus.Offline -> stringResource(R.string.state_offline)
                else -> stringResource(R.string.state_loading)
            },
            style = MaterialTheme.typography.titleMedium,
        )
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
    }
}
