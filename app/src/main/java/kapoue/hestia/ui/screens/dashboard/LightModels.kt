package kapoue.hestia.ui.screens.dashboard

import android.os.SystemClock
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.isActiveNow
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
        /** Origine du dernier changement d'état (`button`, `WS_in`…) : sert à détecter un appui mural. */
        val source: String? = null,
    ) : LightStatus
}

fun RpcResult<LightStatusResult>.toLightStatus(): LightStatus = when (this) {
    is RpcResult.Success -> LightStatus.Online(
        on = value.output,
        brightness = (value.brightness ?: 100.0).roundToInt().coerceIn(0, 100),
        powerWatts = value.apower,
        source = value.source,
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

/**
 * Clé de regroupement du Tableau : adresse + nature. Les canaux light d'un même contrôleur forment
 * leur propre bandeau, indépendant de celui des relais éventuels de la même adresse.
 */
internal fun Device.groupKey(): Pair<String, Boolean> = ipAddress to isLight

/** Position d'un appareil dans l'affichage regroupé (bandeau multi-canaux). */
internal data class GroupFlags(val isFirstInGroup: Boolean, val isMultiChannel: Boolean)

/**
 * Décide, pour chaque appareil (dans l'ordre d'affichage), s'il ouvre un bandeau multi-canaux et
 * s'il en fait partie. Les canaux se regroupent par [groupKey] : relais et lights d'une même
 * adresse forment deux groupes distincts, l'un ne décale jamais la détection du premier de l'autre.
 * Un canal seul (relais ou light) garde sa tuile.
 */
internal fun computeGroupFlags(ordered: List<Device>): List<GroupFlags> {
    val sizeByKey = ordered.groupingBy { it.groupKey() }.eachCount()
    val seen = mutableSetOf<Pair<String, Boolean>>()
    return ordered.map { device ->
        val key = device.groupKey()
        GroupFlags(isFirstInGroup = seen.add(key), isMultiChannel = sizeByKey.getValue(key) > 1)
    }
}

/** Secondes restantes d'un minuteur (arrondi inférieur), null s'il n'y en a pas ou s'il est écoulé. */
internal fun lightTimerRemainingSec(timerEndsAtElapsed: Long?, elapsedNow: Long): Long? {
    if (timerEndsAtElapsed == null) return null
    val remaining = (timerEndsAtElapsed - elapsedNow) / 1000
    return remaining.takeIf { it > 0 }
}

/** Ce que la ligne d'état d'un variateur allumé ajoute : décompte du minuteur, sinon fin du planning (C3). */
sealed interface LightOnDetail {
    data class Timer(val remainingSec: Long) : LightOnDetail
    data class Planned(val endMinutes: Int) : LightOnDetail
    data object None : LightOnDetail
}

internal fun lightOnDetail(timerRemainingSec: Long?, activePlanning: Planning?): LightOnDetail = when {
    timerRemainingSec != null -> LightOnDetail.Timer(timerRemainingSec)
    activePlanning != null -> LightOnDetail.Planned(activePlanning.endMinutes)
    else -> LightOnDetail.None
}

/** Planning précis réellement en cours (présence exclue ; récurrent désactivé aujourd'hui exclu). */
internal fun activeLightPlanning(
    plannings: List<Planning>,
    planningDisabledToday: Boolean,
    isActive: (Planning) -> Boolean = { it.isActiveNow() },
): Planning? = plannings.firstOrNull { !it.isPresence && isActive(it) && !(!it.once && planningDisabledToday) }

/** Appui bouton mural pendant un planning récurrent : vraie transition allumé→éteint (relais et variateurs). */
internal fun disablesPlanningToday(onNow: Boolean, buttonSource: Boolean, wasOn: Boolean, activeRecurringPlanning: Boolean): Boolean =
    !onNow && buttonSource && wasOn && activeRecurringPlanning
