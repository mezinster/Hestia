package kapoue.hestia.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Un planning : une plage horaire pilotée par l'appareil. Deux réalisations possibles côté
 * appareil, invisibles l'une de l'autre pour l'utilisateur (un seul écran, un seul modèle) :
 * - **précis** ([marginMinutes] nul) : composant Schedule natif, deux programmes cron (allumage
 *   au début, extinction à la fin) — ne consomme aucun script, gratuit vis-à-vis de la limite
 *   Shelly de 3 scripts activés par appareil.
 * - **simulation de présence** ([marginMinutes] non nul) : la prise s'allume/s'éteint dans ce
 *   créneau avec une marge aléatoire (±[marginMinutes]) pour imiter une présence réelle — c'est
 *   la seule chose qu'un cron natif ne sait pas faire (une heure fixe, jamais variable). Réalisé
 *   par un script (voir [kapoue.hestia.data.presence.PresenceScriptGenerator]), jamais Unique
 *   (n'aurait pas de sens de « simuler une présence » pour une seule occurrence), jamais combiné
 *   à une coupure sur seuil (mutuellement exclusif, décidé le 2026-08-18 — surveiller la
 *   consommation en plus du jour/marge alourdirait le script pour un besoin jugé trop rare).
 *
 * Hestia ne stocke rien : un planning est toujours **reconstruit** depuis l'appareil (`Schedule.
 * List` pour un précis, le script de présence pour une simulation) — principe du projet, on lit
 * l'état réel, on ne suppose jamais. C'est la règle de non-chevauchement, appliquée à la création
 * (tous types confondus), qui rend cette reconstruction non ambiguë.
 *
 * [days] : jours de la semaine, 0 = dimanche … 6 = samedi. L'ensemble complet = tous les jours.
 * Ignoré quand [date] est non nul (planning **Unique**, une occurrence à cette date précise —
 * seulement pour un planning précis, jamais pour une simulation de présence).
 * [onJobId] / [offJobId] : identifiants des deux programmes cron dans l'appareil (pour la
 * suppression) — null pour une simulation de présence, qui n'a pas de programme cron.
 *
 * [cutoffScriptId] : script dédié de coupure sur seuil (Unique ou récurrent, jamais pour une
 * présence), non nul si une coupure est configurée — id à supprimer avec le planning.
 * [cutoffThresholdW] : seuil relu depuis ce script (null tant qu'il n'a pas encore été relu, voir
 * [DeviceRepository]).
 */
data class Planning(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val days: Set<Int>,
    val onJobId: Int? = null,
    val offJobId: Int? = null,
    val date: LocalDate? = null,
    val cutoffScriptId: Int? = null,
    val cutoffThresholdW: Int? = null,
    val marginMinutes: Int? = null,
) {
    val startMinutes: Int get() = startHour * 60 + startMinute
    val endMinutes: Int get() = endHour * 60 + endMinute
    val everyDay: Boolean get() = days.size >= 7
    val once: Boolean get() = date != null
    val isPresence: Boolean get() = marginMinutes != null

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
    // Simulation de présence (marge non nulle) : élargit la fenêtre de ±marge, pour que
    // « Présence » reste affiché tant que l'horaire réellement tiré au sort par le script peut
    // encore être en cours — sans ça, l'étiquette retombait sur « Actif » générique dès la fin de
    // la fenêtre nominale, alors que la prise peut légitimement rester allumée jusqu'à [marge]
    // minutes de plus (retour de test réel, 2026-08-22). Nul pour un planning précis, sans effet.
    val margin = marginMinutes ?: 0
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    val dow = LocalDate.now().dayOfWeek.value // 1 = lundi … 7 = dimanche
    val cronDayToday = if (dow == 7) 0 else dow // cron : 0 = dimanche … 6 = samedi
    // Place la fenêtre élargie sur une ligne de temps continue ancrée sur le jour [dayOffset]
    // (0 = aujourd'hui, -1 = hier) — un seul mécanisme pour un créneau qui passe minuit par
    // lui-même et pour une marge qui le pousse au-delà (se réduit à l'ancien calcul si marge = 0).
    fun window(dayOffset: Int): IntRange {
        val dayStart = dayOffset * 1440
        val end = if (endMinutes > startMinutes) endMinutes else endMinutes + 1440
        return (dayStart + startMinutes - margin) until (dayStart + end + margin)
    }
    for (dayOffset in -1..0) {
        val cronDay = ((cronDayToday + dayOffset) % 7 + 7) % 7
        if (cronDay in days && nowMin in window(dayOffset)) return true
    }
    return false
}

/** Vrai si ce planning Unique est passé (sa fin est révolue). Toujours faux pour un récurrent. */
fun Planning.isExpiredOnce(): Boolean {
    val date = this.date ?: return false
    return !LocalDateTime.now().isBefore(onceEndAt(date))
}

/** Issue d'une tentative de création de planning (contrôle de conflit inclus). */
sealed interface CreatePlanningResult {
    data object Success : CreatePlanningResult

    /**
     * Chevauche un planning existant (précis ou simulation de présence — un seul type de
     * conflit désormais, [existing] dit lequel via [Planning.isPresence]).
     */
    data class Conflict(val existing: Planning) : CreatePlanningResult

    /** Planning Unique dont la fin (compte tenu d'un éventuel passage minuit) est déjà passée. */
    data object PastOnce : CreatePlanningResult

    /** Le nombre maximum de plannings par appareil est atteint. */
    data object LimitReached : CreatePlanningResult

    /** Échec réseau/RPC : l'appareil n'a pas répondu comme attendu. */
    data object Error : CreatePlanningResult
}
