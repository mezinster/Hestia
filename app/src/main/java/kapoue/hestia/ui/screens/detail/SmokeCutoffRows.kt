package kapoue.hestia.ui.screens.detail

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.DeviceRepository.SmokeCutoffTarget
import kapoue.hestia.data.repository.SmokeTargetBlock
import kapoue.hestia.data.repository.SmokeTargetGate

/**
 * Ligne du sélecteur de coupure sur alarme : un appareil candidat, coché ou non, et la raison
 * éventuelle pour laquelle un détecteur ne peut pas le viser ([block], voir [SmokeTargetGate]).
 */
data class CutoffRow(
    val device: Device,
    val target: SmokeCutoffTarget,
    val checked: Boolean,
    val block: SmokeTargetBlock?,
) {
    /** Une ligne protégée ne peut qu'être décochée : jamais (re)cochée. */
    val enabled: Boolean get() = block == null || checked

    /** Cible déjà configurée mais devenue protégée : à retirer par l'utilisateur. */
    val toReview: Boolean get() = block != null && checked
}

fun cutoffRows(
    candidates: List<Device>,
    targets: List<SmokeCutoffTarget>,
    gate: SmokeTargetGate,
): List<CutoffRow> = candidates.map { device ->
    val target = SmokeCutoffTarget(device.ipAddress, device.switchId)
    CutoffRow(device, target, target in targets, gate.blockOf(device))
}

/** Raison du refus d'une adresse saisie à la main, ou `null` si elle peut être ajoutée. */
fun manualTargetBlock(ip: String, gate: SmokeTargetGate): SmokeTargetBlock? = gate.blockOf(ip.trim())
