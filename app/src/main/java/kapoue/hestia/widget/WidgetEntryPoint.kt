package kapoue.hestia.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.repository.DeviceRepository

/**
 * Accès Hilt aux dépendances depuis les composants du widget (ActionCallback), qui ne sont pas
 * eux-mêmes gérés par Hilt. Récupéré via EntryPointAccessors.fromApplication(...).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun deviceRepository(): DeviceRepository
    fun diagnosticLogger(): DiagnosticLogger
}
