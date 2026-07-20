package kapoue.hestia.widget

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * État persistant d'une instance de widget (stocké via PreferencesGlanceStateDefinition).
 * Le widget affiche l'état **en cache** ; il n'interroge jamais le réseau lors de son rendu
 * (SPEC-V1 § 8 : pas de polling depuis un widget).
 */
object WidgetState {
    val DEVICE_ID = longPreferencesKey("device_id")
    val NAME = stringPreferencesKey("name")
    val STATUS = stringPreferencesKey("status")

    // Valeurs de STATUS.
    const val LOADING = "loading"
    const val ON = "on"
    const val OFF = "off"
    const val OFFLINE = "offline"
    const val PROGRESS = "progress"
    const val PERMISSION = "permission"
    const val DELETED = "deleted"

    const val NO_DEVICE = -1L
}
