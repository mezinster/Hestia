package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.NotifyKind
import kapoue.hestia.domain.model.notifies

/** Notifications de début / fin à émettre pour un planning de ce canal. */
data class PlanningNotify(val start: Boolean, val end: Boolean)

/**
 * Règle unique pour les plannings : une plage de présence suit le seul type [NotifyKind.PRESENCE]
 * (début et fin ensemble), un planning précis suit [NotifyKind.PLANNING_START] /
 * [NotifyKind.PLANNING_END] séparément. Pur, sans effet sur la bascule elle-même.
 */
fun planningNotify(device: Device, isPresence: Boolean): PlanningNotify =
    if (isPresence) {
        val on = device.notifies(NotifyKind.PRESENCE)
        PlanningNotify(on, on)
    } else {
        PlanningNotify(device.notifies(NotifyKind.PLANNING_START), device.notifies(NotifyKind.PLANNING_END))
    }
