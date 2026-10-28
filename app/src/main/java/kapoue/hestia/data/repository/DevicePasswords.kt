package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.prefs.PasswordVault
import kapoue.hestia.data.rpc.PasswordState
import kapoue.hestia.data.rpc.SavedPasswordSource
import kapoue.hestia.data.rpc.StoredPassword

/**
 * Lien entre les mots de passe (coffre, par canal) et les adresses d'appareils. Un mot de passe
 * est propre à l'appareil physique : il est écrit pour tous les canaux d'une même adresse.
 * Ne journalise jamais de mot de passe.
 */
class DevicePasswords(
    private val devices: suspend () -> List<Device>,
    private val vault: PasswordVault,
) : SavedPasswordSource {

    /** Enregistre le mot de passe (état non vérifié) pour tous les canaux de [ip] ; renvoie leur nombre. */
    suspend fun save(ip: String, password: String): Int {
        val channels = devices().filter { it.ipAddress == ip }
        channels.forEach { vault.write(it.id, StoredPassword(password, PasswordState.UNVERIFIED)) }
        return channels.size
    }

    /** Efface le mot de passe de tous les canaux de [ip] ; renvoie leur nombre. */
    suspend fun remove(ip: String): Int {
        val channels = devices().filter { it.ipAddress == ip }
        channels.forEach { vault.write(it.id, null) }
        return channels.size
    }

    fun stateOf(device: Device): PasswordState? = vault.read(device.id)?.state

    fun hasPassword(device: Device): Boolean = vault.read(device.id) != null

    override suspend fun load(): Map<String, StoredPassword> {
        val list = devices()
        val ids = list.map { it.id }.toSet()
        // Purge : canaux supprimés ou import qui a changé les identifiants.
        (vault.knownIds() - ids).forEach { vault.write(it, null) }
        val result = mutableMapOf<String, StoredPassword>()
        for (device in list) {
            val stored = vault.read(device.id) ?: continue
            result[device.ipAddress] = stored
            device.ip2Address?.takeIf { it.isNotEmpty() }?.let { result[it] = stored }
        }
        return result
    }

    override suspend fun markAccepted(address: String) = mark(address, PasswordState.ACCEPTED)

    override suspend fun markRejected(address: String) = mark(address, PasswordState.REJECTED)

    private suspend fun mark(address: String, state: PasswordState) {
        for (device in devices()) {
            if (device.ipAddress != address && device.ip2Address != address) continue
            val stored = vault.read(device.id) ?: continue
            if (stored.state != state) vault.write(device.id, stored.copy(state = state))
        }
    }
}
