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
    /** Simulation de présence en cours — distinct de [plannedLed]/[plannedText] depuis le
     * 2026-08-22 (retour David : un « vrai » planning décidé à l'avance n'est pas la même chose
     * qu'une présence simulée, gardait pourtant la même couleur jusque-là). */
    val presenceLed: Color,
    val presenceText: Color,
    /** Vrai planning (jours/horaires décidés à l'avance) uniquement — un minuteur natif en cours
     * (bouton, Perso/Manuel) est reclassé « Actif » depuis le 2026-08-22, ce n'est pas de la
     * planification au sens de David (« début et fin décidés pour plus tard »). */
    val plannedLed: Color,
    val plannedText: Color,
    val offlineLed: Color,
    val offlineText: Color,
    val offlineBg: Color,
)

private val DarkStateColors = StateColorSet(
    activeLed = Color(0xFF4ADE80), activeText = Color(0xFF4ADE80), activeBg = Color(0xFF1E2B1C),
    idleLed = Color(0xFF5A616B), idleText = Color(0xFF949AA4), idleBg = Color(0xFF232323),
    presenceLed = Color(0xFF85B7EB), presenceText = Color(0xFF85B7EB),
    plannedLed = Color(0xFFAFA9EC), plannedText = Color(0xFFAFA9EC),
    offlineLed = Color(0xFFF0716E), offlineText = Color(0xFFF0716E), offlineBg = Color(0xFF2E1918),
)

private val LightStateColors = StateColorSet(
    activeLed = Color(0xFF22B04B), activeText = Color(0xFF178C46), activeBg = Color(0xFFEAF3DE),
    idleLed = Color(0xFF9AA0A8), idleText = Color(0xFF5A6167), idleBg = Color(0xFFF1EFE8),
    presenceLed = Color(0xFF378ADD), presenceText = Color(0xFF0C447C),
    plannedLed = Color(0xFF7F77DD), plannedText = Color(0xFF534AB7),
    offlineLed = Color(0xFFD8342A), offlineText = Color(0xFFC0342B), offlineBg = Color(0xFFFCEBEB),
)

/** Jeu de couleurs d'état adapté au thème courant. */
val MaterialTheme.stateColors: StateColorSet
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) DarkStateColors else LightStateColors
