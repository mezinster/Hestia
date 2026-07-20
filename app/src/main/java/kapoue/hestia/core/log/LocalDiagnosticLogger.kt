package kapoue.hestia.core.log

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Expose le journal de diagnostic à l'arbre Compose, pour instrumenter la navigation et les
 * événements d'interface sans injecter le logger dans chaque composable. Fourni par MainActivity.
 */
val LocalDiagnosticLogger = staticCompositionLocalOf<DiagnosticLogger> {
    error("DiagnosticLogger non fourni dans le CompositionLocal")
}
