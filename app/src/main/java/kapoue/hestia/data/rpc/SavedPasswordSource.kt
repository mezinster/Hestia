package kapoue.hestia.data.rpc

/** État d'un mot de passe enregistré : jamais essayé, accepté par l'appareil, ou refusé. */
enum class PasswordState { UNVERIFIED, ACCEPTED, REJECTED }

/** Mot de passe enregistré pour un canal, avec son état (persisté avec lui). Ne jamais le journaliser. */
data class StoredPassword(val password: String, val state: PasswordState) {
    // Le mot de passe ne doit jamais fuiter via un toString() (journal, trace d'erreur).
    override fun toString(): String = "StoredPassword(state=$state)"
}

/**
 * Source des mots de passe enregistrés, par adresse d'appareil. Lus depuis le coffre chiffré,
 * jamais supposés. Le registre d'accès y reporte aussi l'issue des authentifications.
 */
interface SavedPasswordSource {
    suspend fun load(): Map<String, StoredPassword>
    suspend fun markAccepted(address: String)
    suspend fun markRejected(address: String)

    companion object {
        /** Source vide et sans effet (tests existants). */
        val NONE: SavedPasswordSource = object : SavedPasswordSource {
            override suspend fun load(): Map<String, StoredPassword> = emptyMap()
            override suspend fun markAccepted(address: String) = Unit
            override suspend fun markRejected(address: String) = Unit
        }
    }
}
