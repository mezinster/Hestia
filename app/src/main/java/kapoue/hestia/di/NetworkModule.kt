package kapoue.hestia.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Client HTTP partagé : timeouts courts (SPEC-V1 § 3), aucune reprise automatique. Utilisé
     * pour le réseau local (RPC Shelly) et, ponctuellement, pour les appels ntfy émis depuis le
     * téléphone (test/validation — l'appareil, lui, appelle ntfy directement, sans passer par ce
     * client). On désactive `retryOnConnectionFailure` pour ne jamais boucler tout seul.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}
