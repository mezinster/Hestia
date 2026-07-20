package kapoue.hestia.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

/** Met à jour l'état persistant des widgets et redemande leur rendu. */
object WidgetUpdater {

    private val widget = HestiaWidget()

    /** Lie une instance de widget à un canal (depuis l'écran de configuration). */
    suspend fun bind(context: Context, glanceId: GlanceId, deviceId: Long, name: String) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetState.DEVICE_ID] = deviceId
            prefs[WidgetState.NAME] = name
            prefs[WidgetState.STATUS] = WidgetState.LOADING
        }
        widget.update(context, glanceId)
    }

    suspend fun setStatus(context: Context, glanceId: GlanceId, status: String, name: String? = null) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetState.STATUS] = status
            if (name != null) prefs[WidgetState.NAME] = name
        }
        widget.update(context, glanceId)
    }

    /**
     * Synchronise tous les widgets à partir des états relevés par l'application ([output] par
     * appareil : true=ON, false=OFF, null=hors ligne). Sans widget, retour immédiat.
     */
    suspend fun syncAll(context: Context, outputByDevice: Map<Long, Boolean?>) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(HestiaWidget::class.java)
        if (ids.isEmpty()) return
        for (id in ids) {
            val bound = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[WidgetState.DEVICE_ID]
                ?: continue
            if (!outputByDevice.containsKey(bound)) continue
            val status = when (outputByDevice[bound]) {
                true -> WidgetState.ON
                false -> WidgetState.OFF
                else -> WidgetState.OFFLINE
            }
            setStatus(context, id, status)
        }
    }
}
