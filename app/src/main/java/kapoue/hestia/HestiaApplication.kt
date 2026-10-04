package kapoue.hestia

import android.app.Application
import android.content.res.Configuration
import dagger.hilt.android.HiltAndroidApp
import kapoue.hestia.core.locale.AppLocaleManager

/** Point d'entrée de l'application, hôte du graphe Hilt. */
@HiltAndroidApp
class HestiaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Android 11–12 : langue choisie dans Réglages appliquée au Context application (sans
        // effet sur 13+, où le système s'en charge). Voir AppLocaleManager.
        AppLocaleManager.applyToApplication(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Un changement de configuration système remet la langue du téléphone : on réapplique.
        AppLocaleManager.applyToApplication(this)
    }
}
