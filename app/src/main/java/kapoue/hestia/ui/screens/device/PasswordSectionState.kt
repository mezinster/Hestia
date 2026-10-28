package kapoue.hestia.ui.screens.device

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.AccessIssue
import kapoue.hestia.data.rpc.PasswordState

/** État du mot de passe d'un appareil, tel qu'affiché dans Modifier l'appareil. */
enum class PasswordSectionState { ACCEPTED, UNVERIFIED, REQUIRED, REJECTED }

/**
 * REJECTED si l'appareil a refusé le mot de passe (problème en cours ou état persisté), REQUIRED s'il
 * en demande un, sinon l'état du mot de passe enregistré ; null si rien (section masquée, D5).
 * Tout autre problème que ceux du mot de passe est ignoré.
 */
fun passwordSectionStateOf(stored: PasswordState?, issue: AccessIssue?): PasswordSectionState? =
    when {
        issue == AccessIssue.PASSWORD_WRONG || stored == PasswordState.REJECTED -> PasswordSectionState.REJECTED
        issue == AccessIssue.PASSWORD_REQUIRED -> PasswordSectionState.REQUIRED
        stored == PasswordState.UNVERIFIED -> PasswordSectionState.UNVERIFIED
        stored == PasswordState.ACCEPTED -> PasswordSectionState.ACCEPTED
        else -> null
    }

/**
 * Problème de mot de passe d'un appareil : accessIssueFor ne renvoie que le problème le plus
 * prioritaire (certificat avant mot de passe), qui masquerait ici un PASSWORD_*. On cherche donc
 * séparément, sur les deux adresses, le premier PASSWORD_WRONG puis PASSWORD_REQUIRED.
 */
fun passwordIssueFor(device: Device, issues: Map<String, AccessIssue>): AccessIssue? {
    val found = listOfNotNull(device.ipAddress, device.ip2Address?.takeIf { it.isNotBlank() }).mapNotNull { issues[it] }
    return listOf(AccessIssue.PASSWORD_WRONG, AccessIssue.PASSWORD_REQUIRED).firstOrNull { it in found }
}
