package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.model.CoverStatusResult
import kotlin.math.abs

/** Durée simulée du mouvement d'un volet non calibré (position inconnue), en ms. */
private const val DEMO_UNCALIBRATED_MOVE_MS = 5_000L

/** Vitesse simulée d'un volet calibré : 10 % par seconde. */
private const val DEMO_PERCENT_PER_SECOND = 10

/** État initial d'un volet démo : `.12` calibré à 60 %, tout autre (`.13`) non calibré. */
internal fun demoCoverStatus(device: Device): CoverStatusResult =
    if (device.ipAddress.endsWith(".12")) {
        CoverStatusResult(id = device.switchId, state = "stopped", currentPos = 60, posControl = true, apower = 0.0)
    } else {
        CoverStatusResult(id = device.switchId, state = "stopped", currentPos = null, posControl = false, apower = 0.0)
    }

/** Mouvement démo en cours, en mémoire uniquement. */
internal data class DemoCoverMove(val from: Int, val target: Int, val startedAtMs: Long)

/** État simulé de [base] à l'instant [nowMs] selon [move] (null : inchangé). */
internal fun demoCoverAt(base: CoverStatusResult, move: DemoCoverMove?, nowMs: Long): CoverStatusResult {
    if (move == null) return base
    val elapsedMs = (nowMs - move.startedAtMs).coerceAtLeast(0L)
    val opening = move.target > move.from
    if (!base.posControl) {
        val finished = elapsedMs >= DEMO_UNCALIBRATED_MOVE_MS
        val state = when {
            !finished -> if (move.target == 100) "opening" else "closing"
            move.target == 100 -> "open"
            else -> "closed"
        }
        return base.copy(state = state, currentPos = null, apower = if (finished) 0.0 else 35.0)
    }
    val travelled = (elapsedMs * DEMO_PERCENT_PER_SECOND / 1_000L).toInt()
    val distance = abs(move.target - move.from)
    if (travelled < distance) {
        val pos = if (opening) move.from + travelled else move.from - travelled
        return base.copy(state = if (opening) "opening" else "closing", currentPos = pos, apower = 35.0)
    }
    val state = when (move.target) {
        100 -> "open"
        0 -> "closed"
        else -> "stopped"
    }
    return base.copy(state = state, currentPos = move.target, apower = 0.0)
}
