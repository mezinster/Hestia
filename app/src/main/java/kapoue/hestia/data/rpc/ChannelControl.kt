package kapoue.hestia.data.rpc

import kapoue.hestia.data.local.entity.Device
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action qui allume/éteint un canal dans un planning (lot C3, 2026-10-07) : `Switch.Set` pour un
 * relais, `Light.Set` pour un variateur. Rend la planification amont générique sans la dupliquer.
 */
enum class ChannelControl(val setMethod: String) {
    SWITCH("Switch.Set"),
    LIGHT("Light.Set"),
}

internal fun channelControlFor(device: Device): ChannelControl =
    if (device.isLight) ChannelControl.LIGHT else ChannelControl.SWITCH

/**
 * Premier appel d'une tâche `Schedule.Create`. Jamais de luminosité pour un variateur : il se
 * rallume à son dernier niveau (choix validé, aucune valeur stockée).
 */
internal fun scheduleActionCall(control: ChannelControl, channelId: Int, on: Boolean): JsonObject = buildJsonObject {
    put("method", control.setMethod)
    put(
        "params",
        buildJsonObject {
            put("id", channelId)
            put("on", on)
        },
    )
}
