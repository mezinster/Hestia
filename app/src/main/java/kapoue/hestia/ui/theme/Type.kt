package kapoue.hestia.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Typographie par défaut Material 3 (police système).
val HestiaTypography = Typography()

/**
 * Style monospace réservé aux **valeurs techniques** : adresses IP, durées, compteurs,
 * numéros de tuile (SPEC-V1 § 5). Ne pas l'utiliser pour du texte courant.
 */
val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
)
