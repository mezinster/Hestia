package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType

/**
 * Champs partagés d'un appareil physique (nom, adresses, type), appliqués à un canal — jamais son
 * nom de canal individuel ([Device.name]) ni rien d'autre, l'épingle HTTPS en particulier.
 */
fun Device.withSharedFields(
    deviceName: String,
    ipAddress: String,
    ip2Address: String?,
    ipName: String?,
    ip2Name: String?,
    type: DeviceType,
): Device = copy(
    deviceName = deviceName,
    ipAddress = ipAddress,
    ip2Address = ip2Address,
    ipName = ipName,
    ip2Name = ip2Name,
    type = type,
)

/** MAC (« Cloud ID ») mis en cache sur un canal, rien d'autre. */
fun Device.withCloudId(macId: String): Device = if (cloudId == macId) this else copy(cloudId = macId)
