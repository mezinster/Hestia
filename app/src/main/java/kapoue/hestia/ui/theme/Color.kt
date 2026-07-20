package kapoue.hestia.ui.theme

import androidx.compose.ui.graphics.Color

// Palette « tableau électrique » dérivée de l'icône (SPEC-V1 § 5).

// Thème clair — fond craie, bleu profond.
val LightBackground = Color(0xFFF4F3EF)
val LightSurface = Color(0xFFFCFBF8)
// Conteneur neutre distinct de la surface (piste d'interrupteur OFF, etc.).
val LightSurfaceVariant = Color(0xFFE9E6DE)
val LightBorder = Color(0xFFDAD7CE)
val LightOnBackground = Color(0xFF14171A)
val LightOnSurfaceVariant = Color(0xFF5A6167)
val LightAccent = Color(0xFF1B3AAB)

// Thème sombre — le bleu profond devient illisible, remplacé par une variante lumineuse.
val DarkBackground = Color(0xFF14161C)
val DarkSurface = Color(0xFF1D212A)
val DarkSurfaceVariant = Color(0xFF262B35)
val DarkBorder = Color(0xFF2C313C)
val DarkOnBackground = Color(0xFFECEAE4)
val DarkOnSurfaceVariant = Color(0xFF949AA4)
val DarkAccent = Color(0xFF6E8FF5)
