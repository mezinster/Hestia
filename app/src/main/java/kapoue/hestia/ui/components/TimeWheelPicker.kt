package kapoue.hestia.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Sélecteur d'heure « à molettes » : deux colonnes (heures 00–23, minutes 00–59) qui défilent au
 * doigt et s'aimantent sur la valeur, avec retour haptique au cran. Fait maison, sans dépendance
 * (contrainte F-Droid). Réutilisable : minuteur « Perso », présence, planning.
 */
@Composable
fun TimeWheelPicker(
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier,
    onChange: (Int, Int) -> Unit,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        WheelColumn(count = 24, value = hour, onValue = { onChange(it, minute) })
        Text(":", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        WheelColumn(count = 60, value = minute, onValue = { onChange(hour, it) })
    }
}

@Composable
private fun WheelColumn(count: Int, value: Int, onValue: (Int) -> Unit) {
    val itemHeight = 44.dp
    val visibleCount = 3
    // Défilement infini : une liste virtuelle géante où l'item i affiche (i % count). On démarre
    // au milieu pour pouvoir tourner « sans fin » dans les deux sens (00 → 59 → 00 …).
    val loopMax = 100_000
    val startIndex = remember { (loopMax / 2).let { it - it % count } + value }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val haptic = LocalHapticFeedback.current

    // Item dont le centre est le plus proche du centre du viewport (robuste, même en boucle).
    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: startIndex
        }
    }
    val centeredValue = ((centeredIndex % count) + count) % count

    // Cran haptique à chaque changement de valeur centrée.
    LaunchedEffect(centeredValue) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    // À l'arrêt du défilement, remonter la valeur retenue.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && centeredValue != value) onValue(centeredValue)
    }

    Box(
        modifier = Modifier
            .width(46.dp)
            .height(itemHeight * visibleCount),
        contentAlignment = Alignment.Center,
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight),
        ) {
            items(loopMax) { index ->
                val isCentered = index == centeredIndex
                Box(modifier = Modifier.fillMaxWidth().height(itemHeight), contentAlignment = Alignment.Center) {
                    Text(
                        text = "%02d".format(index % count),
                        style = if (isCentered) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = if (isCentered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        // Bande de sélection centrale (deux traits), non interactive : le défilement passe dessous.
        Column(verticalArrangement = Arrangement.spacedBy(itemHeight)) {
            HorizontalDivider(modifier = Modifier.width(42.dp))
            HorizontalDivider(modifier = Modifier.width(42.dp))
        }
    }
}
