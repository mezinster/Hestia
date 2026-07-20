package kapoue.hestia.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kapoue.hestia.MainActivity
import kapoue.hestia.R

/**
 * Widget 1×1 (SPEC-V1 § 8) : nom du canal + état + bascule. Affiche l'état **en cache**
 * (jamais d'appel réseau au rendu). L'appui sur la bascule déclenche [ToggleAction], qui fait
 * l'appel RPC en arrière-plan puis met à jour le widget. En cas d'échec/permission refusée,
 * l'appui ouvre l'application (repli).
 */
class HestiaWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val status = prefs[WidgetState.STATUS] ?: WidgetState.LOADING
            WidgetContent(
                name = prefs[WidgetState.NAME].orEmpty(),
                status = status,
                label = context.getString(labelRes(status)),
            )
        }
    }
}

@Composable
private fun WidgetContent(name: String, status: String, label: String) {
    val openApp = actionStartActivity<MainActivity>()
    // États pilotables (on/off/hors-ligne) → bascule ; sinon → ouverture de l'application.
    val bodyAction: Action = when (status) {
        WidgetState.ON, WidgetState.OFF, WidgetState.OFFLINE -> actionRunCallback<ToggleAction>()
        else -> openApp
    }
    val bodyColor = when (status) {
        WidgetState.ON -> ActiveColor
        WidgetState.OFFLINE, WidgetState.PERMISSION -> OfflineColor
        else -> IdleColor
    }

    Column(
        modifier = GlanceModifier.fillMaxSize().background(SurfaceColor).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            maxLines = 1,
            style = TextStyle(fontSize = 11.sp, color = OnSurfaceColor, textAlign = TextAlign.Center),
            modifier = GlanceModifier.fillMaxWidth().clickable(openApp),
        )
        Spacer(GlanceModifier.height(4.dp))
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(28.dp).background(bodyColor).clickable(bodyAction),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = ColorProvider(R.color.widget_state_text)),
            )
        }
    }
}

private fun labelRes(status: String): Int = when (status) {
    WidgetState.ON -> R.string.state_active
    WidgetState.OFF -> R.string.state_idle
    WidgetState.OFFLINE -> R.string.state_offline
    WidgetState.PROGRESS -> R.string.widget_in_progress
    WidgetState.PERMISSION -> R.string.state_permission_required
    WidgetState.DELETED -> R.string.widget_deleted
    else -> R.string.state_loading
}

// Couleurs en ressources : Android résout automatiquement clair/sombre (values / values-night).
private val SurfaceColor = ColorProvider(R.color.widget_surface)
private val OnSurfaceColor = ColorProvider(R.color.widget_on_surface)
private val ActiveColor = ColorProvider(R.color.widget_active)
private val IdleColor = ColorProvider(R.color.widget_idle)
private val OfflineColor = ColorProvider(R.color.widget_offline)
