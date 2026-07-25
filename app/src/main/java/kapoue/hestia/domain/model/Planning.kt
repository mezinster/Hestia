package kapoue.hestia.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * Un planning : une plage horaire récurrente pilotée par l'appareil (composant Schedule natif).
 * Concrètement, deux programmes cron dans l'appareil — allumage au début, extinction à la fin.
 *
 * Hestia ne stocke rien : un planning est toujours **reconstruit** depuis `Schedule.List`
 * (principe du projet — on lit l'état réel, on ne suppose jamais). C'est la règle de non-
 * chevauchement, appliquée à la création, qui rend cette reconstruction non ambiguë.
 *
 * [days] : jours de la semaine, 0 = dimanche … 6 = samedi. L'ensemble complet = tous les jours.
 * [onJobId] / [offJobId] : identifiants des deux programmes dans l'appareil (pour la suppression).
 */
data class Planning(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val days: Set<Int>,
    val onJobId: Int,
    val offJobId: Int,
) {
    val startMinutes: Int get() = startHour * 60 + startMinute
    val endMinutes: Int get() = endHour * 60 + endMinute
    val everyDay: Boolean get() = days.size >= 7
}

/**
 * Vrai si l'heure actuelle (téléphone) tombe dans le créneau de ce planning, un jour où il est
 * actif. Comme les plannings ne se chevauchent jamais, au plus un seul est « en cours » à la fois.
 * Le fuseau de la prise étant identique à celui du téléphone (vérifié), l'heure locale suffit.
 */
fun Planning.isActiveNow(): Boolean {
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    val dow = LocalDate.now().dayOfWeek.value // 1 = lundi … 7 = dimanche
    val cronDay = if (dow == 7) 0 else dow // cron : 0 = dimanche … 6 = samedi
    return if (endMinutes > startMinutes) {
        // Créneau de journée.
        cronDay in days && nowMin >= startMinutes && nowMin < endMinutes
    } else {
        // Créneau à cheval sur minuit : la soirée (jour de début) ou le matin (lendemain).
        val evening = cronDay in days && nowMin >= startMinutes
        val yesterday = (cronDay + 6) % 7
        val morning = yesterday in days && nowMin < endMinutes
        evening || morning
    }
}

/** Issue d'une tentative de création de planning (contrôle de conflit inclus). */
sealed interface CreatePlanningResult {
    data object Success : CreatePlanningResult

    /** Chevauche un planning existant, dont on renvoie les bornes pour le message. */
    data class Conflict(val existing: Planning) : CreatePlanningResult

    /** Une simulation de présence pilote déjà le relais (exclusive avec un planning). */
    data object PresenceActive : CreatePlanningResult

    /** Le nombre maximum de plannings par appareil est atteint. */
    data object LimitReached : CreatePlanningResult

    /** Échec réseau/RPC : l'appareil n'a pas répondu comme attendu. */
    data object Error : CreatePlanningResult
}
