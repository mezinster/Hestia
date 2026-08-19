package kapoue.hestia.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Portée pour un travail qui doit continuer même si l'écran qui l'a déclenché se ferme aussitôt
 * après (ex. redéployer les scripts après un renommage, voir [kapoue.hestia.data.repository.
 * DeviceRepository.resyncDeviceName]) — un `viewModelScope.launch` serait annulé dès la
 * destruction du ViewModel appelant, qui survient presque immédiatement quand l'écran se ferme
 * juste après l'action déclenchante. Bug vécu en direct le 2026-08-19 : le renommage d'un appareil
 * fermait l'écran Modifier aussitôt, tuant la resynchro ntfy avant qu'elle n'ait eu le temps de
 * relire ne serait-ce que les plannings.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
