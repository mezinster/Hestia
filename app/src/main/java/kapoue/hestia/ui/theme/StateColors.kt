package kapoue.hestia.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Couleurs d'état d'un canal (SPEC-V1 § « Couleurs d'état »).
 *
 * Deux valeurs distinctes par état et par thème, plus un fond de tuile (`Bg`) partagé par les
 * états physiques actif/repos : la **LED** (anneau du cercle) peut rester vive (ce n'est pas du
 * texte) tandis que le **texte/libellé** utilise la variante assombrie en thème clair, pour tenir
 * le contraste 4.5:1. Le fond de la tuile suit toujours le fait physique (courant ou non), même
 * si un programme est en cours — celui-ci ne se lit que dans le texte ("Planifié"), jamais dans
 * le fond (2026-08-17, retour de test réel : un fond orange en plus du texte prêtait à
 * confusion). Ne jamais partager les valeurs entre thèmes.
 */
@Immutable
data class StateColorSet(
    val activeLed: Color,
    val activeText: Color,
    val activeBg: Color,
    val idleLed: Color,
    val idleText: Color,
    val idleBg: Color,
    val timedLed: Color,
    val timedText: Color,
    val offlineLed: Color,
    val offlineText: Color,
    val offlineBg: Color,
)

private val DarkStateColors = StateColorSet(
    activeLed = Color(0xFF4ADE80), activeText = Color(0xFF4ADE80), activeBg = Color(0xFF1E2B1C),
    idleLed = Color(0xFF5A616B), idleText = Color(0xFF949AA4), idleBg = Color(0xFF232323),
    timedLed = Color(0xFFF5843F), timedText = Color(0xFFF5843F),
    offlineLed = Color(0xFFF0716E), offlineText = Color(0xFFF0716E), offlineBg = Color(0xFF2E1918),
)

private val LightStateColors = StateColorSet(
    activeLed = Color(0xFF22B04B), activeText = Color(0xFF178C46), activeBg = Color(0xFFEAF3DE),
    idleLed = Color(0xFF9AA0A8), idleText = Color(0xFF5A6167), idleBg = Color(0xFFF1EFE8),
    timedLed = Color(0xFFE8622C), timedText = Color(0xFFC24E1B),
    offlineLed = Color(0xFFD8342A), offlineText = Color(0xFFC0342B), offlineBg = Color(0xFFFCEBEB),
)

/** Jeu de couleurs d'état adapté au thème courant. */
val MaterialTheme.stateColors: StateColorSet
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) DarkStateColors else LightStateColors
