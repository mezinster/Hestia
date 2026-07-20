package kapoue.hestia.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Couleurs d'état d'un canal (SPEC-V1 § « Couleurs d'état »).
 *
 * Deux valeurs distinctes par état et par thème : la **LED** peut rester vive (ce n'est pas
 * du texte) tandis que le **texte/libellé** utilise la variante assombrie en thème clair,
 * pour tenir le contraste 4.5:1. Ne jamais partager les valeurs entre thèmes.
 */
@Immutable
data class StateColorSet(
    val activeLed: Color,
    val activeText: Color,
    val idleLed: Color,
    val idleText: Color,
    val timedLed: Color,
    val timedText: Color,
    val offlineLed: Color,
    val offlineText: Color,
)

private val DarkStateColors = StateColorSet(
    activeLed = Color(0xFF4ADE80), activeText = Color(0xFF4ADE80),
    idleLed = Color(0xFF5A616B), idleText = Color(0xFF949AA4),
    timedLed = Color(0xFFF5843F), timedText = Color(0xFFF5843F),
    offlineLed = Color(0xFFF0716E), offlineText = Color(0xFFF0716E),
)

private val LightStateColors = StateColorSet(
    activeLed = Color(0xFF22B04B), activeText = Color(0xFF178C46),
    idleLed = Color(0xFF9AA0A8), idleText = Color(0xFF5A6167),
    timedLed = Color(0xFFE8622C), timedText = Color(0xFFC24E1B),
    offlineLed = Color(0xFFD8342A), offlineText = Color(0xFFC0342B),
)

/** Jeu de couleurs d'état adapté au thème courant. */
val MaterialTheme.stateColors: StateColorSet
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) DarkStateColors else LightStateColors
