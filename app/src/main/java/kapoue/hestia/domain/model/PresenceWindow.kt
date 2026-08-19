package kapoue.hestia.domain.model

/**
 * Une plage de simulation de présence : la prise s'allume/s'éteint dans ce créneau, les jours
 * indiqués, avec une marge aléatoire (±[marginMinutes]) pour imiter une présence réelle.
 *
 * Type interne à [kapoue.hestia.data.presence.PresenceScriptGenerator] (forme du script, pas le
 * modèle exposé à l'UI — voir [Planning], qui porte désormais aussi bien les plannings précis que
 * les simulations de présence, converti vers/depuis ce type à la frontière du dépôt). Une prise
 * peut avoir plusieurs plages (matin + soir, par exemple) ; toutes sont embarquées dans un unique
 * script sur l'appareil, d'où elles sont relues (Hestia ne suppose jamais).
 *
 * [days] : jours de la semaine, 0 = dimanche … 6 = samedi (même convention que [Planning.days] —
 * pratique, `Date.getDay()` en JS suit exactement la même numérotation, aucune conversion à faire
 * côté script). [endMinutes] < [startMinutes] = créneau qui passe minuit (ex. 22h → 6h).
 */
data class PresenceWindow(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val marginMinutes: Int,
    val days: Set<Int>,
) {
    val startMinutes: Int get() = startHour * 60 + startMinute
    val endMinutes: Int get() = endHour * 60 + endMinute
}
