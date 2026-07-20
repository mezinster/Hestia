package kapoue.hestia.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.android.EntryPointAccessors
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.ui.permission.LocalNetworkPermission

/**
 * Bascule déclenchée par l'appui sur le widget. Exécutée en arrière-plan par Glance : elle fait
 * l'appel RPC, puis met à jour l'état affiché. Construction défensive (SPEC-V1 § 8) : si la
 * permission réseau local n'est pas utilisable (cas Android 17 en arrière-plan) ou si l'appel
 * échoue, le widget repli sur un état non pilotable — l'appui suivant ouvre l'application.
 */
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val repository = entryPoint.deviceRepository()
        val logger = entryPoint.diagnosticLogger()

        val deviceId = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetState.DEVICE_ID]
            ?: return
        val device = repository.getDevice(deviceId)
        if (device == null) {
            WidgetUpdater.setStatus(context, glanceId, WidgetState.DELETED)
            return
        }
        if (!LocalNetworkPermission.isUsable(context)) {
            WidgetUpdater.setStatus(context, glanceId, WidgetState.PERMISSION, device.name)
            return
        }

        val previousStatus = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WidgetState.STATUS]
        val turnOn = previousStatus != WidgetState.ON

        WidgetUpdater.setStatus(context, glanceId, WidgetState.PROGRESS, device.name)
        logger.info("widget", "Bascule ${device.ipAddress}#${device.switchId} → $turnOn")

        val newStatus = when (repository.userToggle(device, turnOn)) {
            is RpcResult.Success -> if (turnOn) WidgetState.ON else WidgetState.OFF
            else -> WidgetState.OFFLINE
        }
        WidgetUpdater.setStatus(context, glanceId, newStatus, device.name)
    }
}
