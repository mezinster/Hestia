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

    /** Même créneau : mêmes heure, minute, jours et date (l'action est ignorée). */
    fun sameSlotAs(other: CoverEvent): Boolean =
        hour == other.hour && minute == other.minute && days == other.days && date == other.date
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
