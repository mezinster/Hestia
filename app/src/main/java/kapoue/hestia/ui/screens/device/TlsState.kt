package kapoue.hestia.ui.screens.device

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.accessIssueFor
import kapoue.hestia.data.rpc.AccessIssue

/** État de la connexion chiffrée d'un appareil, tel qu'affiché dans Modifier l'appareil. */
enum class TlsState { TRUSTED, REQUIRED, CHANGED }

/**
 * CHANGED si le certificat ne correspond plus à l'épingle, REQUIRED si l'appareil exige HTTPS sans
 * épingle, TRUSTED si une épingle est enregistrée, sinon null (appareil en HTTP : section masquée).
 */
fun tlsStateOf(device: Device, issues: Map<String, AccessIssue>): TlsState? =
    when {
        accessIssueFor(device, issues) == AccessIssue.CERT_CHANGED -> TlsState.CHANGED
        accessIssueFor(device, issues) == AccessIssue.HTTPS_REQUIRED -> TlsState.REQUIRED
        device.certPin != null -> TlsState.TRUSTED
        else -> null
    }
