package kapoue.hestia.domain.model

import kapoue.hestia.data.local.entity.Device

/**
 * Types de notification qu'un utilisateur peut couper par appareil. Chaque type occupe un bit de
 * [Device.notifyMask]. Couper un type ne change jamais le comportement de l'appareil (coupures,
 * minuteurs, plannings, présence) : seuls les envois ntfy / notifications locales disparaissent.
 */
enum class NotifyKind(val bit: Int) {
    PLANNING_START(1),
    PLANNING_END(2),
    CUTOFF(4),
    TIMER_END(8),
    PRESENCE(16),
    COVER_MOVES(32),
    ;

    companion object {
        /** Tous les types activés (valeur par défaut). */
        const val ALL = 63
    }
}

fun Int.allows(kind: NotifyKind): Boolean = this and kind.bit != 0

fun Int.withKind(kind: NotifyKind, enabled: Boolean): Int =
    if (enabled) this or kind.bit else this and kind.bit.inv()

/** Un détecteur de fumée n'a aucun réglage et n'est jamais filtré. */
fun Device.notifies(kind: NotifyKind): Boolean =
    type == DeviceType.SMOKE_DETECTOR || notifyMask.allows(kind)

/** Types réglables pour cet appareil, dans l'ordre de l'enum. */
fun applicableKinds(device: Device): List<NotifyKind> = when {
    device.type == DeviceType.SMOKE_DETECTOR -> emptyList()
    device.isCover -> listOf(NotifyKind.COVER_MOVES)
    device.isLight -> listOf(NotifyKind.PLANNING_START, NotifyKind.PLANNING_END)
    device.supportsSwitch -> buildList {
        add(NotifyKind.PLANNING_START)
        add(NotifyKind.PLANNING_END)
        if (device.hasScripting && device.hasPowerMetering) add(NotifyKind.CUTOFF)
        add(NotifyKind.TIMER_END)
        if (device.hasScripting) add(NotifyKind.PRESENCE)
    }
    else -> emptyList()
}
