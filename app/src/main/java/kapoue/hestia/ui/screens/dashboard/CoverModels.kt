package kapoue.hestia.ui.screens.dashboard

import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.CoverStatusResult

/** Mouvement d'un volet tel que rapporté par `Cover.GetStatus.state`. */
enum class CoverMotion { OPEN, CLOSED, OPENING, CLOSING, STOPPED, CALIBRATING, UNKNOWN }

/** Traduit l'état brut de l'appareil ; tout état inattendu devient [CoverMotion.UNKNOWN]. */
internal fun coverMotionOf(state: String?): CoverMotion = when (state) {
    "open" -> CoverMotion.OPEN
    "closed" -> CoverMotion.CLOSED
    "opening" -> CoverMotion.OPENING
    "closing" -> CoverMotion.CLOSING
    "stopped" -> CoverMotion.STOPPED
    "calibrating" -> CoverMotion.CALIBRATING
    else -> CoverMotion.UNKNOWN
}

/** Défauts traduits ; tout code non reconnu (ex. `cal_abort:timeout`) devient [OTHER]. */
enum class CoverFault { OBSTRUCTION, OVERPOWER, OVERTEMP, VOLTAGE, SAFETY_SWITCH, OTHER }

internal fun coverFaultOf(code: String): CoverFault = when (code) {
    "obstruction" -> CoverFault.OBSTRUCTION
    "overpower" -> CoverFault.OVERPOWER
    "overtemp" -> CoverFault.OVERTEMP
    "overvoltage", "undervoltage" -> CoverFault.VOLTAGE
    "safety_switch" -> CoverFault.SAFETY_SWITCH
    else -> CoverFault.OTHER
}

/** État d'un volet pour l'interface. [Online.position] est null tant que le volet n'est pas calibré. */
sealed interface CoverStatus {
    data object Loading : CoverStatus
    data object Offline : CoverStatus
    data class Online(
        val motion: CoverMotion,
        val position: Int?,
        val positionControl: Boolean,
        val powerWatts: Double?,
        val faults: List<CoverFault>,
    ) : CoverStatus
}

fun RpcResult<CoverStatusResult>.toCoverStatus(): CoverStatus = when (this) {
    is RpcResult.Success -> CoverStatus.Online(
        motion = coverMotionOf(value.state),
        position = value.currentPos?.coerceIn(0, 100),
        positionControl = value.posControl,
        powerWatts = value.apower,
        faults = value.errors.map(::coverFaultOf),
    )
    else -> CoverStatus.Offline
}

/** Cadence de relevé : rapide (1,5 s) en mouvement ou calibration, sinon 5 s. */
internal fun coverPollIntervalMs(status: CoverStatus): Long {
    val moving = status is CoverStatus.Online && status.motion in setOf(
        CoverMotion.OPENING, CoverMotion.CLOSING, CoverMotion.CALIBRATING,
    )
    return if (moving) 1_500L else 5_000L
}
