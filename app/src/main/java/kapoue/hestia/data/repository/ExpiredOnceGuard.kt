package kapoue.hestia.data.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * Plannings Unique expirés déjà pris en charge pendant cette session (2026-10-29, idée reprise
 * d'upstream 2.16.2). Plusieurs relevés concurrents (Tableau, Détail, worker de notifications)
 * peuvent voir le même Unique expiré : seul le premier le supprime de l'appareil, les autres
 * l'ignorent — sinon autant de `Schedule.Delete` en double, refusés en erreur -103 au journal.
 *
 * Clé = adresse + identifiant fourni par l'appelant (les jobs d'un planning), jamais réutilisée :
 * un id de job supprimé n'est pas réattribué par l'appareil dans la même session. Les volets n'en
 * ont pas besoin, leurs lectures purgeantes passent sous le verrou de l'appareil.
 */
class ExpiredOnceGuard {
    private val handled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Garde de [items] ceux qu'aucun autre relevé n'a encore réclamés pour [ip]. */
    fun <T> claim(ip: String, items: List<T>, key: (T) -> String): List<T> =
        items.filter { handled.add("$ip#${key(it)}") }
}
