package kapoue.hestia.ui.screens.dashboard

import android.os.SystemClock
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kotlin.math.roundToInt

/** État d'un canal variateur (2026-10-07) — distinct de [TileStatus], construit pour les relais. */
sealed interface LightStatus {
    data object Loading : LightStatus
    data object Offline : LightStatus
    /** [timerEndsAtElapsed] : fin du minuteur (référentiel SystemClock.elapsedRealtime, ms), null sans minuteur. */
    data class Online(
        val on: Boolean,
        val brightness: Int,
        val powerWatts: Double?,
        val timerEndsAtElapsed: Long? = null,
    ) : LightStatus
}

fun RpcResult<LightStatusResult>.toLightStatus(): LightStatus = when (this) {
    is RpcResult.Success -> LightStatus.Online(
        on = value.output,
        brightness = (value.brightness ?: 100.0).roundToInt().coerceIn(0, 100),
        powerWatts = value.apower,
        // Horloges lues seulement si un minuteur tourne (tests JVM sans Android sinon).
        timerEndsAtElapsed = if (value.timerStartedAt != null && value.timerDuration != null) {
            lightTimerEndsAt(value.timerStartedAt, value.timerDuration, System.currentTimeMillis() / 1000.0, SystemClock.elapsedRealtime())
        } else {
            null
        },
    )
    else -> LightStatus.Offline
}

/**
 * Fin d'un minuteur `toggle_after` dans le référentiel elapsedRealtime (ms), ou null s'il est absent
 * ou déjà écoulé — même calcul que pour un relais, horloges passées en paramètre pour être testable.
 */
internal fun lightTimerEndsAt(startedAt: Double?, duration: Double?, nowEpochSec: Double, nowElapsedMs: Long): Long? {
    if (startedAt == null || duration == null) return null
    val remainingSec = (startedAt + duration - nowEpochSec).toLong()
    if (remainingSec <= 0) return null
    return nowElapsedMs + remainingSec * 1000
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

/** Secondes restantes d'un minuteur (arrondi inférieur), null s'il n'y en a pas ou s'il est écoulé. */
internal fun lightTimerRemainingSec(timerEndsAtElapsed: Long?, elapsedNow: Long): Long? {
    if (timerEndsAtElapsed == null) return null
    val remaining = (timerEndsAtElapsed - elapsedNow) / 1000
    return remaining.takeIf { it > 0 }
}
