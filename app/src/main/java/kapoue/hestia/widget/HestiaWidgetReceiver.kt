package kapoue.hestia.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Récepteur système du widget Hestia (déclaré dans le manifeste). */
class HestiaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HestiaWidget()
}
