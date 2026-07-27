package kapoue.hestia.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Sélecteur « à molette » sur une liste de valeurs prédéfinies (ex. seuils de consommation en
 * Watts), même mécanique que [TimeWheelPicker] (défilement infini, accroche, retour haptique)
 * mais généralisée à une liste arbitraire plutôt qu'à un intervalle 0..N.
 */
@Composable
fun ValueWheelPicker(
    values: List<Int>,
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val count = values.size
    val itemHeight = 44.dp
    val visibleCount = 3
    val loopMax = 100_000
    val initialIndex = values.indexOf(value).coerceAtLeast(0)
    val startIndex = remember { (loopMax / 2).let { it - it % count } + initialIndex }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val haptic = LocalHapticFeedback.current

    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: startIndex
        }
    }
    val centeredValue = values[centeredIndex % count]

    LaunchedEffect(centeredValue) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && centeredValue != value) onChange(centeredValue)
    }

    Box(
        modifier = modifier
            .width(64.dp)
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
                        text = values[index % count].toString(),
                        style = if (isCentered) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = if (isCentered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        // Bande de sélection centrale (deux traits), non interactive : le défilement passe dessous.
        Column(verticalArrangement = Arrangement.spacedBy(itemHeight)) {
            HorizontalDivider(modifier = Modifier.width(60.dp))
            HorizontalDivider(modifier = Modifier.width(60.dp))
        }
    }
}
