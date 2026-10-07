package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device

/** Composant qui porte le nom d'un canal sur l'appareil (variateurs C1, 2026-10-07). */
internal enum class ChannelNameRpc {
    /** `Switch.GetConfig` / `Switch.SetConfig` (relais). */
    SWITCH,

    /** `Light.GetConfig` / `Light.SetConfig` (variateur). */
    LIGHT,

    /** `Cover.GetConfig` / `Cover.SetConfig` (volet). */
    COVER,

    /** Pas de nom de canal côté appareil (détecteur de fumée…), ou appareil démo : aucun appel. */
    NONE,
}

internal fun channelNameRpc(device: Device): ChannelNameRpc = when {
    device.ipAddress.startsWith(DeviceRepository.DEMO_IP_PREFIX) -> ChannelNameRpc.NONE
    device.isCover -> ChannelNameRpc.COVER
    device.isLight -> ChannelNameRpc.LIGHT
    device.supportsSwitch -> ChannelNameRpc.SWITCH
    else -> ChannelNameRpc.NONE
}
