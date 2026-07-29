package kapoue.hestia.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Un planning : une plage horaire pilotée par l'appareil (composant Schedule natif). Concrètement,
 * deux programmes cron dans l'appareil — allumage au début, extinction à la fin.
 *
 * Hestia ne stocke rien : un planning est toujours **reconstruit** depuis `Schedule.List`
 * (principe du projet — on lit l'état réel, on ne suppose jamais). C'est la règle de non-
 * chevauchement, appliquée à la création, qui rend cette reconstruction non ambiguë.
 *
 * [days] : jours de la semaine, 0 = dimanche … 6 = samedi. L'ensemble complet = tous les jours.
 * Ignoré quand [date] est non nul (planning **Unique**, une occurrence à cette date précise).
 * [onJobId] / [offJobId] : identifiants des deux programmes dans l'appareil (pour la suppression).
 *
 * [cutoffScriptId] : script dédié de coupure sur seuil (Unique ou récurrent), non nul si une
 * coupure est configurée — id à supprimer avec le planning. [cutoffThresholdW] : seuil relu
 * depuis ce script (null tant qu'il n'a pas encore été relu, voir [DeviceRepository]).
 */
data class Planning(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val days: Set<Int>,
    val onJobId: Int,
    val offJobId: Int,
    val date: LocalDate? = null,
    val cutoffScriptId: Int? = null,
    val cutoffThresholdW: Int? = null,
) {
    val startMinutes: Int get() = startHour * 60 + startMinute
    val endMinutes: Int get() = endHour * 60 + endMinute
    val everyDay: Boolean get() = days.size >= 7
    val once: Boolean get() = date != null

    /** Instant de fin réel d'un planning Unique (lendemain si le créneau passe minuit). */
    internal fun onceEndAt(onceDate: LocalDate): LocalDateTime = onceEndAt(startHour, startMinute, endHour, endMinute, onceDate)
}

/**
 * Instant de fin d'un créneau Unique (lendemain si le créneau passe minuit), calculable **avant**
 * qu'un [Planning] existe — utilisé côté repository pour refuser la création d'un créneau déjà passé.
 */
fun onceEndAt(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, date: LocalDate): LocalDateTime =
    if (endHour * 60 + endMinute > startHour * 60 + startMinute) date.atTime(endHour, endMinute)
    else date.plusDays(1).atTime(endHour, endMinute)

/**
 * Vrai si l'heure actuelle (téléphone) tombe dans le créneau de ce planning, un jour où il est
 * actif. Comme les plannings ne se chevauchent jamais, au plus un seul est « en cours » à la fois.
 * Le fuseau de la prise étant identique à celui du téléphone (vérifié), l'heure locale suffit.
 */
fun Planning.isActiveNow(): Boolean {
    val date = this.date
    if (date != null) {
        val now = LocalDateTime.now()
        val startAt = date.atTime(startHour, startMinute)
        return !now.isBefore(startAt) && now.isBefore(onceEndAt(date))
    }
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

/** Vrai si ce planning Unique est passé (sa fin est révolue). Toujours faux pour un récurrent. */
fun Planning.isExpiredOnce(): Boolean {
    val date = this.date ?: return false
    return !LocalDateTime.now().isBefore(onceEndAt(date))
}

/** Issue d'une tentative de création de planning (contrôle de conflit inclus). */
sealed interface CreatePlanningResult {
    data object Success : CreatePlanningResult

    /** Chevauche un planning existant, dont on renvoie les bornes pour le message. */
    data class Conflict(val existing: Planning) : CreatePlanningResult

    /** Le créneau chevauche une plage de simulation de présence. */
    data object PresenceOverlap : CreatePlanningResult

    /** Planning Unique dont la fin (compte tenu d'un éventuel passage minuit) est déjà passée. */
    data object PastOnce : CreatePlanningResult

    /** Le nombre maximum de plannings par appareil est atteint. */
    data object LimitReached : CreatePlanningResult

    /** Échec réseau/RPC : l'appareil n'a pas répondu comme attendu. */
    data object Error : CreatePlanningResult
}
