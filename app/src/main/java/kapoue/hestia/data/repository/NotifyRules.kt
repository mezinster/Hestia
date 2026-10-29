package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.presence.ButtonTimerScriptGenerator
import kapoue.hestia.data.presence.ChargeScriptGenerator
import kapoue.hestia.data.presence.PresenceScriptGenerator
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

/*
 * Configs relues dans la mémoire vive d'un script (réalignement, script périmé) : un script
 * antérieur aux drapeaux de notif les relit tous à vrai. La ligne Device du canal (même switchId
 * parmi [rows], les canaux d'un même appareil physique) fait foi quand elle existe ; sans ligne,
 * la valeur relue est gardée telle quelle.
 */

fun applyMaskToButtonConfigs(
    configs: List<ButtonTimerScriptGenerator.ChannelConfig>,
    rows: List<Device>,
): List<ButtonTimerScriptGenerator.ChannelConfig> = configs.map { c ->
    val row = rows.firstOrNull { it.switchId == c.switchId } ?: return@map c
    c.copy(notifyEnd = row.notifies(NotifyKind.TIMER_END), notifyCutoff = row.notifies(NotifyKind.CUTOFF))
}

fun applyMaskToPresenceConfigs(
    configs: List<PresenceScriptGenerator.ChannelConfig>,
    rows: List<Device>,
): List<PresenceScriptGenerator.ChannelConfig> = configs.map { c ->
    val row = rows.firstOrNull { it.switchId == c.switchId } ?: return@map c
    c.copy(notify = row.notifies(NotifyKind.PRESENCE))
}

/** Notif de fin coupée = corps vidé (le texte d'origine n'est pas recalculable, jamais rétabli ici). */
fun applyMaskToChargeConfigs(
    configs: List<ChargeScriptGenerator.ChannelConfig>,
    rows: List<Device>,
): List<ChargeScriptGenerator.ChannelConfig> = configs.map { c ->
    val row = rows.firstOrNull { it.switchId == c.switchId } ?: return@map c
    c.copy(
        endBody = if (row.notifies(NotifyKind.TIMER_END)) c.endBody else "",
        notifyCutoff = row.notifies(NotifyKind.CUTOFF),
    )
}
