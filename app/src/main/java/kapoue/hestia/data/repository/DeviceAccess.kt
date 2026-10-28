package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.AccessIssue

/**
 * Épingles à enregistrer dans le registre d'accès : adresse → épingle. Les DEUX adresses d'un
 * appareil sont couvertes (un échec TLS sur l'adresse 1 fait basculer sur l'adresse 2, qui doit
 * donc être épinglée elle aussi).
 */
fun pinsByAddress(devices: List<Device>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (d in devices) {
        val pin = d.certPin ?: continue
        result[d.ipAddress] = pin
        d.ip2Address?.takeIf { it.isNotBlank() }?.let { result[it] = pin }
    }
    return result
}

/** Ordre de gravité des problèmes d'accès : le premier de la liste l'emporte, sur les deux adresses. */
private val ISSUE_PRIORITY = listOf(
    AccessIssue.CERT_CHANGED,
    AccessIssue.HTTPS_REQUIRED,
    AccessIssue.PASSWORD_WRONG,
    AccessIssue.PASSWORD_REQUIRED,
    AccessIssue.THROTTLED,
    AccessIssue.PASSWORD_UNVERIFIED,
)

/** Problème d'accès d'un appareil : adresses 1 et 2 confondues, le plus grave l'emporte ([ISSUE_PRIORITY]). */
fun accessIssueFor(device: Device, issues: Map<String, AccessIssue>): AccessIssue? {
    val first = issues[device.ipAddress]
    val second = device.ip2Address?.takeIf { it.isNotBlank() }?.let { issues[it] }
    return ISSUE_PRIORITY.firstOrNull { it == first || it == second }
}
