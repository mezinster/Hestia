package kapoue.hestia.domain.model

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
