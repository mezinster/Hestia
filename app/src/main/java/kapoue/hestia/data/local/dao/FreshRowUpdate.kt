package kapoue.hestia.data.local.dao

import kapoue.hestia.data.local.entity.Device

/**
 * Réécrit les lignes [ids] à partir de leur état ACTUEL en base, jamais d'un instantané gardé en
 * mémoire : une copie périmée (écran Modifier ouvert avant « Faire confiance » ou « Oublier »)
 * écraserait sinon les colonnes écrites entre-temps, l'épingle HTTPS en tête (revue finale C1, F2).
 * Une ligne disparue entre-temps est ignorée ; une ligne inchangée par [transform] n'est pas écrite.
 * Renvoie le nombre de lignes écrites. Pure (lecture/écriture injectées), pour être testable sans Room.
 */
suspend fun updateFreshRows(
    ids: List<Long>,
    read: suspend (Long) -> Device?,
    write: suspend (Device) -> Unit,
    transform: (Device) -> Device,
): Int {
    var written = 0
    for (id in ids) {
        val current = read(id) ?: continue
        val next = transform(current)
        if (next != current) {
            write(next)
            written++
        }
    }
    return written
}
