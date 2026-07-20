package kapoue.hestia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Indique si le thème sombre est actif, pour sélectionner les jeux de couleurs d'état. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightSurface,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightBorder,
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkBorder,
)

/**
 * Thème de l'application. Suit le thème système (clair/sombre) — le choix manuel est prévu
 * pour un lot ultérieur. **Pas de couleurs dynamiques Material You** : la palette est fixe,
 * dérivée de l'identité visuelle.
 */
@Composable
fun HestiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = HestiaTypography,
            content = content,
        )
    }
}
