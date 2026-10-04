package kapoue.hestia.core.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.domain.model.AppLanguage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Langue de l'interface choisie dans Réglages, indépendamment de celle du téléphone. Elle doit
 * valoir pour TOUT le processus, pas seulement les écrans : les textes ntfy écrits dans les
 * scripts des appareils et les notifications du worker sont produits via le Context application.
 *
 * - Android 13+ : réglage natif « Langue de l'appli » ([LocaleManager]). Le système le mémorise,
 *   l'applique à tous les Context et recrée l'Activity ; un changement fait dans les Paramètres
 *   du téléphone est donc vu ici aussi. Rien n'est stocké côté Hestia.
 * - Android 11–12 : aucun équivalent système. Choix mémorisé dans `hestia_prefs`, appliqué à
 *   l'Activity ([activityOverride]) et au Context application ([applyToApplication]).
 */
@Singleton
class AppLocaleManager @Inject constructor(@ApplicationContext private val context: Context) {

    fun current(): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            AppLanguage.fromTag(if (locales.isEmpty) null else locales[0].toLanguageTag())
        } else {
            storedLanguage(context)
        }

    /** @return true si l'appelant doit recréer l'Activity (Android 11–12 ; sur 13+ le système le fait). */
    fun set(language: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                forcedLocales(language) ?: LocaleList.getEmptyLocaleList()
            return false
        }
        // commit() et non apply() : la valeur doit être sur disque avant le recreate() qui suit,
        // relue dans attachBaseContext de la nouvelle Activity.
        prefs(context).edit(commit = true) {
            if (language.tag == null) remove(KEY_APP_LANGUAGE) else putString(KEY_APP_LANGUAGE, language.tag)
        }
        applyToApplication(context)
        return true
    }

    companion object {
        /** Même fichier que AppPreferences ; lu ici sans Hilt car utilisé dès attachBaseContext. */
        private const val PREFS_NAME = "hestia_prefs"
        private const val KEY_APP_LANGUAGE = "app_language"

        private fun isLegacy() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

        /**
         * Liste à imposer pour une langue forcée, en gardant la région du système (« en-GB »…)
         * pour que dates et heures restent au format régional ; voir [preferredTagFor].
         */
        private fun forcedLocales(language: AppLanguage): LocaleList? {
            val system = Resources.getSystem().configuration.locales
            val tag = preferredTagFor(language, (0 until system.size()).map { system[it].toLanguageTag() })
            return tag?.let { LocaleList.forLanguageTags(it.replace('_', '-')) }
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun storedLanguage(context: Context): AppLanguage =
            AppLanguage.fromTag(prefs(context).getString(KEY_APP_LANGUAGE, null))

        /**
         * Langues effectives sur Android 11–12 : celle choisie, sinon la liste COMPLÈTE du SYSTÈME
         * (lue sur Resources.getSystem(), jamais sur les ressources de l'appli, qui portent encore
         * la dernière langue forcée quand on revient à « Langue du système »). On garde toute la
         * liste, pas seulement la première : si Hestia n'a pas la 1re langue du téléphone, la
         * résolution des ressources retombe sur la 2e, la 3e… comme sans surcharge.
         */
        private fun legacyLocales(context: Context): LocaleList =
            forcedLocales(storedLanguage(context)) ?: Resources.getSystem().configuration.locales

        /**
         * Configuration à passer à `applyOverrideConfiguration` dans `attachBaseContext` de
         * l'Activity (Android 11–12), ou null sur 13+ (le système s'en charge). Configuration()
         * vierge : seuls les champs renseignés (ici la locale) surchargent ceux du système.
         */
        fun activityOverride(context: Context): Configuration? {
            if (!isLegacy()) return null
            return Configuration().apply { setLocales(legacyLocales(context)) }
        }

        /**
         * Applique la langue au Context application (Android 11–12 seulement). updateConfiguration
         * est déprécié mais reste le seul moyen sous l'API 33 ; à rappeler après chaque changement
         * de configuration système (rotation, thème, police…), qui remet la langue du téléphone.
         */
        @Suppress("DEPRECATION")
        fun applyToApplication(context: Context) {
            if (!isLegacy()) return
            val app = context.applicationContext
            val locales = legacyLocales(app)
            LocaleList.setDefault(locales)
            val resources = app.resources
            val config = Configuration(resources.configuration).apply { setLocales(locales) }
            resources.updateConfiguration(config, resources.displayMetrics)
        }
    }
}
