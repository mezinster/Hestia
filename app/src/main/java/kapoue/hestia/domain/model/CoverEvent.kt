package kapoue.hestia.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kapoue.hestia.data.rpc.ScheduleCodec

/** Action d'un événement de programmation d'un volet. */
sealed interface CoverEventAction {
    /** Ouverture complète. */
    data object Open : CoverEventAction

    /** Fermeture complète. */
    data object Close : CoverEventAction

    /** Déplacement à une position donnée (0 = fermé, 100 = ouvert). */
    data class GoTo(val position: Int) : CoverEventAction
}

/**
 * Règle du domaine : les sept jours cochés équivalent à « tous les jours », représenté par un
 * ensemble vide. Appliquée par tous les créateurs d'événements (dialogue, relecture de l'appareil,
 * mémo des pauses) et par [CoverEvent.sameSlotAs].
 */
internal fun normalizeCoverDays(days: Set<Int>): Set<Int> = if ((0..6).all { it in days }) emptySet() else days

/**
 * Un événement de programmation d'un volet, exécuté par l'appareil lui-même.
 *
 * Jours au format cron (0 = dimanche … 6 = samedi). [days] vide et [date] nul signifient
 * « tous les jours ». Si [date] est renseignée, l'événement est unique à cette date.
 * [jobId] est l'identifiant du planning sur l'appareil, nul tant qu'il n'est pas créé.
 */
data class CoverEvent(
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val date: LocalDate? = null,
    val action: CoverEventAction,
    val jobId: Int? = null,
) {
    val isOnce: Boolean get() = date != null

    /** Vrai si l'événement est unique et que son instant n'est plus strictement dans le futur. */
    fun isExpiredOnce(now: LocalDateTime): Boolean {
        val d = date ?: return false
        return !LocalDateTime.of(d, LocalTime.of(hour, minute)).isAfter(now)
    }

    /** Prochain instant d'exécution strictement après [now], ou nul si l'événement unique est passé. */
    fun nextOccurrence(now: LocalDateTime): LocalDateTime? {
        val time = LocalTime.of(hour, minute)
        if (date != null) {
            val at = LocalDateTime.of(date, time)
            return at.takeIf { it.isAfter(now) }
        }
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (days.isNotEmpty() && day.dayOfWeek.value % 7 !in days) continue
            val at = LocalDateTime.of(day, time)
            if (at.isAfter(now)) return at
        }
        return null
    }

    /** Même créneau : mêmes heure, minute, jours (sept jours = tous les jours) et date (l'action est ignorée). */
    fun sameSlotAs(other: CoverEvent): Boolean =
        hour == other.hour && minute == other.minute &&
            normalizeCoverDays(days) == normalizeCoverDays(other.days) && date == other.date
}

/**
 * Ordre d'affichage : premier jour actif (lundi d'abord, via [ScheduleCodec.displayRank]),
 * un événement unique étant rangé sur son jour réel, puis l'heure.
 */
internal val coverEventDisplayOrder: Comparator<CoverEvent> =
    compareBy<CoverEvent> { e ->
        val d = e.date
        when {
            d != null -> ScheduleCodec.displayRank(d.dayOfWeek.value % 7)
            e.days.isEmpty() -> -1 // « tous les jours » avant lundi
            else -> e.days.minOf { ScheduleCodec.displayRank(it) }
        }
    }.thenBy { it.hour }.thenBy { it.minute }

/**
 * Construit les deux événements d'une plage « ouvrir à / fermer à ». En mode inversé, la fermeture
 * vient d'abord (heure « fermer à ») puis l'ouverture. Les deux partagent jours et date.
 */
internal fun windowEvents(
    openHour: Int,
    openMinute: Int,
    closeHour: Int,
    closeMinute: Int,
    days: Set<Int>,
    date: LocalDate?,
    inverted: Boolean,
): Pair<CoverEvent, CoverEvent> {
    val open = CoverEvent(openHour, openMinute, days, date, CoverEventAction.Open)
    val close = CoverEvent(closeHour, closeMinute, days, date, CoverEventAction.Close)
    return if (inverted) close to open else open to close
}

/** Intervalle minimal entre deux relectures des événements pendant le relevé périodique. */
internal const val COVER_EVENTS_RELOAD_MS = 30_000L

/** Vrai au premier relevé, puis au plus une fois toutes les 30 s (les actions relisent d'elles-mêmes). */
internal fun shouldReloadCoverEvents(lastLoadMs: Long?, nowMs: Long): Boolean =
    lastLoadMs == null || nowMs - lastLoadMs >= COVER_EVENTS_RELOAD_MS

/**
 * Toutes les occurrences de [event] dans la fenêtre `(from, to]` : [from] exclu, [to] inclus.
 * Parcourt chaque date de la fenêtre, donc valable aussi au-delà d'une journée.
 */
internal fun coverEventInstantsBetween(event: CoverEvent, from: LocalDateTime, to: LocalDateTime): List<LocalDateTime> {
    val time = LocalTime.of(event.hour, event.minute)
    val result = mutableListOf<LocalDateTime>()
    var day = from.toLocalDate()
    val last = to.toLocalDate()
    while (!day.isAfter(last)) {
        val matches = when {
            event.date != null -> day == event.date
            event.days.isEmpty() -> true
            else -> day.dayOfWeek.value % 7 in event.days
        }
        if (matches) {
            val at = LocalDateTime.of(day, time)
            if (at.isAfter(from) && !at.isAfter(to)) result += at
        }
        day = day.plusDays(1)
    }
    return result
}

/**
 * Délai de grâce avant de purger un unique échu de l'appareil : même valeur que la fenêtre de
 * rattrapage du worker de notifications (`NotificationWorker.MAX_LOOKBACK_MS`), pour qu'il ait
 * encore le temps de le lire et de le notifier.
 */
internal const val COVER_ONCE_PURGE_GRACE_MS = 30L * 60 * 1000

/** Vrai si l'unique est échu depuis plus de [COVER_ONCE_PURGE_GRACE_MS] (purge autorisée). */
internal fun CoverEvent.isPurgeableOnce(now: LocalDateTime): Boolean {
    val d = date ?: return false
    val at = LocalDateTime.of(d, LocalTime.of(hour, minute))
    return at.plusNanos(COVER_ONCE_PURGE_GRACE_MS * 1_000_000).isBefore(now)
}

/**
 * Sépare les événements lus en (renvoyés, à purger). Lecture normale : les uniques échus ne sont
 * plus renvoyés, et sont purgés seulement 30 min après leur échéance ([COVER_ONCE_PURGE_GRACE_MS]).
 * Lecture du worker de notifications ([keepExpired]) : ils sont gardés (ils viennent justement de
 * s'exécuter) et rien n'est purgé, une lecture normale ultérieure s'en charge.
 */
internal fun splitForRead(
    all: List<CoverEvent>,
    now: LocalDateTime,
    keepExpired: Boolean,
): Pair<List<CoverEvent>, List<CoverEvent>> {
    if (keepExpired) return all to emptyList()
    return all.filterNot { it.isExpiredOnce(now) } to all.filter { it.isPurgeableOnce(now) }
}
