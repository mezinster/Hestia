package kapoue.hestia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.log.LocalDiagnosticLogger
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.domain.model.ThemeMode
import kapoue.hestia.ui.HestiaApp
import kapoue.hestia.ui.theme.HestiaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Unique Activity de l'application (architecture single-activity + Compose). */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var diagnosticLogger: DiagnosticLogger

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by appPreferences.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // enableEdgeToEdge() ne fixe la couleur des icônes de la barre de statut qu'une fois,
            // au démarrage, selon le thème SYSTÈME — il ignore le réglage propre à Hestia
            // (Réglages → Apparence), qui peut diverger. Sans ça, icônes blanches sur fond clair
            // = invisibles. On la recale à chaque changement de thème effectif.
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            HestiaTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(LocalDiagnosticLogger provides diagnosticLogger) {
                    HestiaApp()
                }
            }
        }
    }
}
