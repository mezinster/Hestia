package kapoue.hestia.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.log.RoomDiagnosticLogger
import javax.inject.Singleton

/** Lie l'interface de journal à l'implémentation Room (tampon circulaire, lot 5). */
@Module
@InstallIn(SingletonComponent::class)
abstract class LogModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticLogger(impl: RoomDiagnosticLogger): DiagnosticLogger
}
