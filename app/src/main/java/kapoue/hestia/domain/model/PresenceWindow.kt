package kapoue.hestia.domain.model

import java.time.LocalTime

/**
 * Une plage de simulation de présence : la prise s'allume/s'éteint dans ce créneau, chaque jour,
 * avec une marge aléatoire (±[marginMinutes]) pour imiter une présence réelle.
 *
 * Une prise peut en avoir plusieurs (matin + soir, par exemple). Toutes sont embarquées dans un
 * unique script sur l'appareil, d'où elles sont relues (Hestia ne suppose jamais).
 *
 * [endMinutes] < [startMinutes] = créneau qui passe minuit (ex. 22h → 6h).
 */
data class PresenceWindow(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val marginMinutes: Int,
) {
    val startMinutes: Int get() = startHour * 60 + startMinute
    val endMinutes: Int get() = endHour * 60 + endMinute
}

/** Issue d'une opération présence (ajout/édition), pour l'UI. */
sealed interface PresenceOpResult {
    data object Success : PresenceOpResult

    /** La plage chevauche un planning (horaire fixe qui ne doit pas être contredit). */
    data object PlanningOverlap : PresenceOpResult

    /** Échec réseau/RPC. */
    data object Error : PresenceOpResult
}

/** Vrai si l'heure actuelle (téléphone) tombe dans la plage — tous les jours, minuit géré. */
fun PresenceWindow.isActiveNow(): Boolean {
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    return if (endMinutes > startMinutes) {
        nowMin >= startMinutes && nowMin < endMinutes
    } else {
        nowMin >= startMinutes || nowMin < endMinutes
    }
}
