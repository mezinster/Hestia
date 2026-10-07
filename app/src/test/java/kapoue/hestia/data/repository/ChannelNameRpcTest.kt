package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Quel appel RPC porte le nom d'un canal (C1, 2026-10-07). */
class ChannelNameRpcTest {

    private fun device(isLight: Boolean, supportsSwitch: Boolean, ip: String = "192.168.1.50") =
        Device(name = "X", ipAddress = ip, type = if (isLight) DeviceType.LAMP else DeviceType.PLUG, isLight = isLight, supportsSwitch = supportsSwitch)

    @Test
    fun `un relais passe par Switch`() = assertEquals(ChannelNameRpc.SWITCH, channelNameRpc(device(isLight = false, supportsSwitch = true)))

    @Test
    fun `un variateur passe par Light`() = assertEquals(ChannelNameRpc.LIGHT, channelNameRpc(device(isLight = true, supportsSwitch = false)))

    @Test
    fun `un canal sans relais ni light n'a pas de nom de canal`() =
        assertEquals(ChannelNameRpc.NONE, channelNameRpc(device(isLight = false, supportsSwitch = false)))

    @Test
    fun `un appareil demo n'appelle jamais le reseau`() =
        assertEquals(ChannelNameRpc.NONE, channelNameRpc(device(isLight = true, supportsSwitch = false, ip = "203.0.113.7")))
}
