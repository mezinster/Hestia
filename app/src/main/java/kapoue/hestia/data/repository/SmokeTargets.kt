package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.AccessIssue

/** Pourquoi une adresse ne peut pas être la cible d'un webhook de détecteur de fumée. */
enum class SmokeTargetBlock {
    /** Appareil protégé par mot de passe : un webhook natif ne sait pas s'authentifier. */
    AUTH,

    /** Appareil en HTTPS (épinglé) : un webhook natif en HTTP clair n'aboutirait pas. */
    HTTPS,
}

/**
 * Règle pure « cible interdite » : un webhook natif du détecteur appelle l'appareil visé en HTTP
 * simple, sans identifiants. Un appareil protégé (mot de passe enregistré ou exigé, certificat
 * épinglé ou HTTPS exigé) ne peut donc pas être visé. La règle porte sur l'adresse ET sur l'autre
 * adresse d'un même appareil connu. [AUTH] l'emporte sur [HTTPS] quand les deux s'appliquent.
 */
data class SmokeTargetGate(
    val devices: List<Device>,
    val issues: Map<String, AccessIssue>,
    val passwordAddresses: Set<String>,
) {
    fun blockOf(address: String): SmokeTargetBlock? {
        // Toutes les adresses de l'appareil connu qui porte [address] (ou juste [address] seule).
        val owners = devices.filter { it.ipAddress == address || it.ip2Address == address }
        val addresses = (owners.flatMap { listOf(it.ipAddress, it.ip2Address) } + address)
            .filterNotNull().filter { it.isNotBlank() }.toSet()
        val authIssues = setOf(
            AccessIssue.PASSWORD_REQUIRED, AccessIssue.PASSWORD_WRONG,
            AccessIssue.PASSWORD_UNVERIFIED, AccessIssue.THROTTLED,
        )
        val httpsIssues = setOf(AccessIssue.HTTPS_REQUIRED, AccessIssue.CERT_CHANGED)
        if (addresses.any { it in passwordAddresses || issues[it] in authIssues }) return SmokeTargetBlock.AUTH
        if (owners.any { it.certPin != null } || addresses.any { issues[it] in httpsIssues }) {
            return SmokeTargetBlock.HTTPS
        }
        return null
    }

    fun blockOf(device: Device): SmokeTargetBlock? = blockOf(device.ipAddress)

    companion object {
        val OPEN = SmokeTargetGate(emptyList(), emptyMap(), emptySet())
    }
}

/** URL du webhook de coupure : `http://<ip>/rpc/Switch.Set?id=<n>&on=false`, jamais d'identifiants. */
fun cutoffTargetUrl(target: DeviceRepository.SmokeCutoffTarget): String =
    "http://${target.ip}/rpc/Switch.Set?id=${target.switchId}&on=false"

/** Décode une URL écrite par [cutoffTargetUrl], ou `null` si c'est autre chose. */
fun parseCutoffTargetUrl(url: String): DeviceRepository.SmokeCutoffTarget? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (!uri.path.endsWith("/rpc/Switch.Set")) return null
    val id = (uri.query ?: return null).split("&")
        .mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 } }
        .firstOrNull { it[0] == "id" }
        ?.get(1)?.toIntOrNull() ?: return null
    return DeviceRepository.SmokeCutoffTarget(uri.host ?: return null, id)
}

/** URL du webhook de relais ntfy : appelle `notifySmoke` du script relais, jamais d'identifiants. */
fun relayHookUrl(relayIp: String, scriptId: Int, event: String, mac: String): String {
    val code = java.net.URLEncoder.encode("notifySmoke(\"$event\",\"$mac\")", "UTF-8")
    return "http://$relayIp/rpc/Script.Eval?id=$scriptId&code=$code"
}
