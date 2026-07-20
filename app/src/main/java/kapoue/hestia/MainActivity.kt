package kapoue.hestia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.log.LocalDiagnosticLogger
import kapoue.hestia.ui.HestiaApp
import kapoue.hestia.ui.theme.HestiaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Unique Activity de l'application (architecture single-activity + Compose). */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var diagnosticLogger: DiagnosticLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HestiaTheme {
                CompositionLocalProvider(LocalDiagnosticLogger provides diagnosticLogger) {
                    HestiaApp()
                }
            }
        }
    }
}
