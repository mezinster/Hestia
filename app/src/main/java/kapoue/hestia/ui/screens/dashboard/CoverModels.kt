package kapoue.hestia.ui.screens.dashboard

import kapoue.hestia.R
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.CoverStatusResult
import kapoue.hestia.domain.model.CoverEventAction
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kapoue.hestia.domain.model.CoverEvent

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
    "overpower", "overcurrent" -> CoverFault.OVERPOWER
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
        /** Position visée par la commande en cours (0–100), null si inconnue. */
        val target: Int? = null,
    ) : CoverStatus
}

fun RpcResult<CoverStatusResult>.toCoverStatus(): CoverStatus = when (this) {
    is RpcResult.Success -> CoverStatus.Online(
        motion = coverMotionOf(value.state),
        position = value.currentPos?.coerceIn(0, 100),
        positionControl = value.posControl,
        powerWatts = value.apower,
        faults = value.errors.map(::coverFaultOf),
        target = value.targetPos?.coerceIn(0, 100),
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

/** Le prochain événement n'est ajouté au libellé que d'un volet en ligne et pas en calibration. */
internal fun coverShowsNext(status: CoverStatus): Boolean =
    status is CoverStatus.Online && status.motion != CoverMotion.CALIBRATING

/** Chaîne du prochain événement : selon l'action, et « à HH:MM » (aujourd'hui) ou « jour HH:MM ». */
@androidx.annotation.StringRes
internal fun coverNextStringRes(next: NextCoverEvent): Int = when (next.action) {
    CoverEventAction.Open -> if (next.today) R.string.cover_next_open_today else R.string.cover_next_open_day
    CoverEventAction.Close -> if (next.today) R.string.cover_next_close_today else R.string.cover_next_close_day
    is CoverEventAction.GoTo -> if (next.today) R.string.cover_next_goto_today else R.string.cover_next_goto_day
}

/**
 * Prochain événement à afficher sur la tuile : [today] vrai si [at] tombe le jour même, [farOff]
 * vrai s'il tombe plus de 6 jours plus tard (le nom du jour serait alors ambigu).
 */
internal data class NextCoverEvent(
    val action: CoverEventAction,
    val at: LocalDateTime,
    val today: Boolean,
    val farOff: Boolean = false,
)

/** Comment désigner le jour du prochain événement sur la tuile. */
internal enum class CoverNextDay { TODAY, WEEKDAY, DATE }

/** « à HH:MM » aujourd'hui, nom du jour dans les 6 jours, date courte au-delà (unique lointain). */
internal fun coverNextDay(next: NextCoverEvent): CoverNextDay = when {
    next.today -> CoverNextDay.TODAY
    next.farOff -> CoverNextDay.DATE
    else -> CoverNextDay.WEEKDAY
}

/**
 * Événement le plus proche dans le temps parmi [events] (les uniques expirés sont ignorés par
 * [CoverEvent.nextOccurrence]), ou nul s'il n'y en a aucun.
 */
internal fun nextCoverEvent(events: List<CoverEvent>, now: LocalDateTime): NextCoverEvent? =
    events.mapNotNull { e -> e.nextOccurrence(now)?.let { e to it } }
        .minByOrNull { it.second }
        ?.let { (e, at) ->
            val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
            NextCoverEvent(e.action, at, today = days == 0L, farOff = days > 6)
        }
