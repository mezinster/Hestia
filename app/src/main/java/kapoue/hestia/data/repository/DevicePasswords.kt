package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.prefs.PasswordVault
import kapoue.hestia.data.rpc.PasswordState
import kapoue.hestia.data.rpc.SavedPasswordSource
import kapoue.hestia.data.rpc.StoredPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lien entre les mots de passe (coffre, par canal) et les adresses d'appareils. Un mot de passe
 * est propre à l'appareil physique : il est écrit pour tous les canaux d'une même adresse.
 * Ne journalise jamais de mot de passe. Tout accès au coffre (préférences chiffrées, disque) passe
 * sur [Dispatchers.IO] : les appelants sont souvent sur le thread principal (viewModelScope).
 */
class DevicePasswords(
    private val devices: suspend () -> List<Device>,
    private val vault: PasswordVault,
) : SavedPasswordSource {

    /**
     * Enregistre le mot de passe pour tous les canaux de [ip] ; renvoie leur nombre. [state] vaut
     * `ACCEPTED` seulement quand l'appareil vient d'accepter ce mot de passe (ajout).
     */
    suspend fun save(ip: String, password: String, state: PasswordState = PasswordState.UNVERIFIED): Int {
        val channels = devices().filter { it.ipAddress == ip }
        io { channels.forEach { vault.write(it.id, StoredPassword(password, state)) } }
        return channels.size
    }

    /** Efface le mot de passe de tous les canaux de [ip] ; renvoie leur nombre. */
    suspend fun remove(ip: String): Int {
        val channels = devices().filter { it.ipAddress == ip }
        io { channels.forEach { vault.write(it.id, null) } }
        return channels.size
    }

    /** Efface le mot de passe d'un seul canal par son id (utile après suppression de la ligne). */
    suspend fun removeId(deviceId: Long) = io { vault.write(deviceId, null) }

    suspend fun stateOf(device: Device): PasswordState? = io { vault.read(device.id)?.state }

    suspend fun hasPassword(device: Device): Boolean = io { vault.read(device.id) != null }

    override suspend fun load(): Map<String, StoredPassword> {
        val list = devices()
        val ids = list.map { it.id }.toSet()
        return io {
            // Purge : canaux supprimés ou import qui a changé les identifiants.
            (vault.knownIds() - ids).forEach { vault.write(it, null) }
            val result = mutableMapOf<String, StoredPassword>()
            for (device in list) {
                val stored = vault.read(device.id) ?: continue
                result[device.ipAddress] = stored
                device.ip2Address?.takeIf { it.isNotEmpty() }?.let { result[it] = stored }
            }
            result
        }
    }

    override suspend fun markAccepted(address: String, expectedPassword: String) =
        mark(address, expectedPassword, PasswordState.ACCEPTED)

    override suspend fun markRejected(address: String, expectedPassword: String) =
        mark(address, expectedPassword, PasswordState.REJECTED)

    private suspend fun mark(address: String, expectedPassword: String, state: PasswordState) {
        val list = devices()
        io {
            for (device in list) {
                if (device.ipAddress != address && device.ip2Address != address) continue
                val stored = vault.read(device.id) ?: continue
                // Résultat périmé (mot de passe changé entre-temps) : on n'y touche pas.
                if (stored.password != expectedPassword) continue
                if (stored.state != state) vault.write(device.id, stored.copy(state = state))
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
