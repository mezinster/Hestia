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

/** Problème d'accès d'un appareil : adresse 1, sinon adresse 2 ; [AccessIssue.CERT_CHANGED] l'emporte. */
fun accessIssueFor(device: Device, issues: Map<String, AccessIssue>): AccessIssue? {
    val first = issues[device.ipAddress]
    val second = device.ip2Address?.takeIf { it.isNotBlank() }?.let { issues[it] }
    return if (first == AccessIssue.CERT_CHANGED || second == AccessIssue.CERT_CHANGED) {
        AccessIssue.CERT_CHANGED
    } else {
        first ?: second
    }
}
