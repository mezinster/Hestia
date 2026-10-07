package kapoue.hestia.ui.screens.dashboard

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kotlin.math.roundToInt

/** État d'un canal variateur (2026-10-07) — distinct de [TileStatus], construit pour les relais. */
sealed interface LightStatus {
    data object Loading : LightStatus
    data object Offline : LightStatus
    data class Online(val on: Boolean, val brightness: Int, val powerWatts: Double?) : LightStatus
}

fun RpcResult<LightStatusResult>.toLightStatus(): LightStatus = when (this) {
    is RpcResult.Success -> LightStatus.Online(
        on = value.output,
        brightness = (value.brightness ?: 100.0).roundToInt().coerceIn(0, 100),
        powerWatts = value.apower,
    )
    else -> LightStatus.Offline
}

/** Un canal light a toujours sa propre tuile, jamais le bandeau multi-canaux (pensé pour les relais). */
internal fun Device.isGroupable(): Boolean = !isLight

/** Position d'un appareil dans l'affichage regroupé (bandeau multi-canaux). */
internal data class GroupFlags(val isFirstInGroup: Boolean, val isMultiChannel: Boolean)

/**
 * Décide, pour chaque appareil (dans l'ordre d'affichage), s'il ouvre un bandeau multi-canaux et
 * s'il en fait partie. Seuls les relais se regroupent par adresse : un light n'est jamais compté
 * dans un groupe et ne décale pas la détection du premier relais d'un groupe.
 */
internal fun computeGroupFlags(ordered: List<Device>): List<GroupFlags> {
    val sizeByIp = ordered.filter { it.isGroupable() }.groupingBy { it.ipAddress }.eachCount()
    var lastIp: String? = null
    return ordered.map { device ->
        if (!device.isGroupable()) {
            GroupFlags(isFirstInGroup = true, isMultiChannel = false)
        } else {
            val first = device.ipAddress != lastIp
            lastIp = device.ipAddress
            GroupFlags(isFirstInGroup = first, isMultiChannel = sizeByIp.getValue(device.ipAddress) > 1)
        }
    }
}
