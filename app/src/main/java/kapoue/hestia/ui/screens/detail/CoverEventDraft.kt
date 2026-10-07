package kapoue.hestia.ui.screens.detail

import java.time.DayOfWeek
import java.time.LocalDate
import androidx.annotation.StringRes
import kapoue.hestia.R
import kapoue.hestia.data.repository.CoverEventResult
import kapoue.hestia.data.rpc.ScheduleCodec
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import kapoue.hestia.domain.model.normalizeCoverDays
import kapoue.hestia.domain.model.windowEvents

/*
 * Logique pure du dialogue d'ajout/modification d'un événement de volet (lot S2, 2026-10-07) :
 * construction des événements à partir de l'état du dialogue et choix du libellé des jours.
 * Séparée de l'UI pour être testée sans Compose.
 */

/** Quand l'événement se répète : tous les jours, certains jours, ou une seule fois à une date. */
internal sealed interface CoverDaysChoice {
    data object EveryDay : CoverDaysChoice
    data class Days(val days: Set<Int>) : CoverDaysChoice
    data class Once(val date: LocalDate) : CoverDaysChoice

    /** Faux seulement pour une sélection de jours vide (rien à enregistrer). */
    val isValid: Boolean get() = this !is Days || days.isNotEmpty()
}

/** Action choisie dans le dialogue ; la position vient d'un curseur à part. */
internal enum class CoverActionChoice { Open, Close, Position }

private fun CoverDaysChoice.daysAndDate(): Pair<Set<Int>, LocalDate?> = when (this) {
    CoverDaysChoice.EveryDay -> emptySet<Int>() to null
    is CoverDaysChoice.Days -> normalizeCoverDays(days) to null
    is CoverDaysChoice.Once -> emptySet<Int>() to date
}

/** Événement unique d'après l'état du dialogue ; [position] est borné à 0–100. */
internal fun buildCoverEvent(
    hour: Int,
    minute: Int,
    whenChoice: CoverDaysChoice,
    action: CoverActionChoice,
    position: Int,
): CoverEvent {
    val (days, date) = whenChoice.daysAndDate()
    val act = when (action) {
        CoverActionChoice.Open -> CoverEventAction.Open
        CoverActionChoice.Close -> CoverEventAction.Close
        CoverActionChoice.Position -> CoverEventAction.GoTo(position.coerceIn(0, 100))
    }
    return CoverEvent(hour, minute, days, date, act)
}

/**
 * Plage « ouvrir à / fermer à » : les heures sont celles des deux sélecteurs dans l'ordre affiché
 * (le premier est « Ouvrir à », ou « Fermer à » si [inverted]). Renvoie les deux événements dans
 * l'ordre de [windowEvents].
 */
internal fun buildCoverWindow(
    firstHour: Int,
    firstMinute: Int,
    secondHour: Int,
    secondMinute: Int,
    whenChoice: CoverDaysChoice,
    inverted: Boolean,
): Pair<CoverEvent, CoverEvent> {
    val (days, date) = whenChoice.daysAndDate()
    // windowEvents attend (ouvrir, fermer) ; en mode inversé le premier sélecteur est « fermer ».
    return if (inverted) {
        windowEvents(secondHour, secondMinute, firstHour, firstMinute, days, date, inverted = true)
    } else {
        windowEvents(firstHour, firstMinute, secondHour, secondMinute, days, date, inverted = false)
    }
}

/** Choix de jours correspondant à un événement existant (préremplissage de l'édition). */
internal fun coverDaysChoiceOf(event: CoverEvent): CoverDaysChoice = when {
    event.date != null -> CoverDaysChoice.Once(event.date)
    event.days.isEmpty() -> CoverDaysChoice.EveryDay
    else -> CoverDaysChoice.Days(event.days)
}

/** Libellé des jours d'une ligne de la liste, avant résolution des textes localisés. */
internal sealed interface CoverDaysLabel {
    data object EveryDay : CoverDaysLabel
    /** Jours dans l'ordre d'affichage, lundi d'abord. */
    data class Days(val days: List<DayOfWeek>) : CoverDaysLabel
    data class OnDate(val date: LocalDate) : CoverDaysLabel
}

internal fun coverDaysLabel(event: CoverEvent): CoverDaysLabel {
    event.date?.let { return CoverDaysLabel.OnDate(it) }
    val valid = event.days.filter { it in 0..6 }
    if (valid.isEmpty() || valid.toSet().size == 7) return CoverDaysLabel.EveryDay
    return CoverDaysLabel.Days(
        valid.toSet()
            .sortedBy { ScheduleCodec.displayRank(it) }
            .map { DayOfWeek.of(if (it == 0) 7 else it) },
    )
}

/** Message d'erreur d'un résultat de programmation, nul pour un succès. */
@StringRes
internal fun coverEventResultMessage(result: CoverEventResult): Int? = when (result) {
    CoverEventResult.Success -> null
    CoverEventResult.PastOnce -> R.string.cover_event_error_past
    CoverEventResult.Duplicate -> R.string.cover_event_error_duplicate
    CoverEventResult.LimitReached -> R.string.cover_event_error_limit
    CoverEventResult.Error -> R.string.cover_event_error_generic
}

/** Position non disponible (volet non calibré) : on ne peut pas enregistrer cette action. */
internal fun canSaveCoverEvent(action: CoverActionChoice, canPosition: Boolean): Boolean =
    action != CoverActionChoice.Position || canPosition

/** Nombre maximal d'événements actifs par volet (les événements en pause ne comptent pas). */
internal const val COVER_EVENT_MAX = 10

internal fun canAddCoverEvent(activeCount: Int): Boolean = activeCount + 1 <= COVER_EVENT_MAX

/** Une plage crée deux événements : elle exige deux places libres. */
internal fun canAddCoverWindow(activeCount: Int): Boolean = activeCount + 2 <= COVER_EVENT_MAX
