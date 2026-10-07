package kapoue.hestia.ui.screens.detail

import java.time.LocalDate
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverEventDraftTest {

    private val date = LocalDate.of(2026, 10, 12) // un lundi

    @Test
    fun `evenement unique tous les jours`() {
        val e = buildCoverEvent(7, 30, CoverDaysChoice.EveryDay, CoverActionChoice.Open, 50)
        assertEquals(CoverEvent(7, 30, emptySet(), null, CoverEventAction.Open), e)
    }

    @Test
    fun `evenement sur des jours precis`() {
        val e = buildCoverEvent(21, 0, CoverDaysChoice.Days(setOf(1, 3)), CoverActionChoice.Close, 50)
        assertEquals(CoverEvent(21, 0, setOf(1, 3), null, CoverEventAction.Close), e)
    }

    @Test
    fun `evenement unique a une date n'a pas de jours`() {
        val e = buildCoverEvent(8, 0, CoverDaysChoice.Once(date), CoverActionChoice.Open, 50)
        assertEquals(CoverEvent(8, 0, emptySet(), date, CoverEventAction.Open), e)
        assertTrue(e.isOnce)
    }

    @Test
    fun `position porte la valeur choisie`() {
        val e = buildCoverEvent(12, 0, CoverDaysChoice.EveryDay, CoverActionChoice.Position, 35)
        assertEquals(CoverEventAction.GoTo(35), e.action)
    }

    @Test
    fun `position est bornee entre 0 et 100`() {
        assertEquals(CoverEventAction.GoTo(100), buildCoverEvent(1, 0, CoverDaysChoice.EveryDay, CoverActionChoice.Position, 140).action)
        assertEquals(CoverEventAction.GoTo(0), buildCoverEvent(1, 0, CoverDaysChoice.EveryDay, CoverActionChoice.Position, -5).action)
    }

    @Test
    fun `plage ouvre puis ferme`() {
        val (first, second) = buildCoverWindow(7, 0, 22, 0, CoverDaysChoice.Days(setOf(2)), inverted = false)
        assertEquals(CoverEvent(7, 0, setOf(2), null, CoverEventAction.Open), first)
        assertEquals(CoverEvent(22, 0, setOf(2), null, CoverEventAction.Close), second)
    }

    @Test
    fun `plage inversee ferme puis ouvre`() {
        val (first, second) = buildCoverWindow(22, 0, 7, 0, CoverDaysChoice.EveryDay, inverted = true)
        assertEquals(CoverEventAction.Close, first.action)
        assertEquals(CoverEventAction.Open, second.action)
        // Les heures suivent les sélecteurs : le premier est « Fermer à », le second « Ouvrir à ».
        assertEquals(22, first.hour)
        assertEquals(7, second.hour)
    }

    @Test
    fun `plage unique partage la date`() {
        val (a, b) = buildCoverWindow(9, 0, 18, 0, CoverDaysChoice.Once(date), inverted = false)
        assertEquals(date, a.date)
        assertEquals(date, b.date)
        assertTrue(a.days.isEmpty() && b.days.isEmpty())
    }

    @Test
    fun `choix de jours valide seulement avec au moins un jour`() {
        assertTrue(CoverDaysChoice.EveryDay.isValid)
        assertTrue(CoverDaysChoice.Once(date).isValid)
        assertTrue(CoverDaysChoice.Days(setOf(0)).isValid)
        assertFalse(CoverDaysChoice.Days(emptySet()).isValid)
    }

    @Test
    fun `reprise du choix de jours depuis un evenement`() {
        assertEquals(CoverDaysChoice.EveryDay, coverDaysChoiceOf(CoverEvent(1, 0, action = CoverEventAction.Open)))
        assertEquals(CoverDaysChoice.Days(setOf(1, 5)), coverDaysChoiceOf(CoverEvent(1, 0, setOf(1, 5), null, CoverEventAction.Open)))
        assertEquals(CoverDaysChoice.Once(date), coverDaysChoiceOf(CoverEvent(1, 0, emptySet(), date, CoverEventAction.Open)))
    }

    @Test
    fun `libelle des jours - vide donne tous les jours`() {
        assertEquals(CoverDaysLabel.EveryDay, coverDaysLabel(CoverEvent(1, 0, action = CoverEventAction.Open)))
    }

    @Test
    fun `libelle des jours - sept jours donnent tous les jours`() {
        val all = setOf(0, 1, 2, 3, 4, 5, 6)
        assertEquals(CoverDaysLabel.EveryDay, coverDaysLabel(CoverEvent(1, 0, all, null, CoverEventAction.Open)))
    }

    @Test
    fun `libelle des jours - ordre lundi d'abord et dimanche en dernier`() {
        val label = coverDaysLabel(CoverEvent(1, 0, setOf(0, 6, 1), null, CoverEventAction.Open))
        assertEquals(CoverDaysLabel.Days(listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)), label)
    }

    @Test
    fun `libelle des jours - la date prime`() {
        assertEquals(CoverDaysLabel.OnDate(date), coverDaysLabel(CoverEvent(1, 0, setOf(1), date, CoverEventAction.Open)))
    }

    @Test
    fun `enregistrement - position indisponible bloque seulement l'action position`() {
        assertFalse(canSaveCoverEvent(CoverActionChoice.Position, canPosition = false))
        assertTrue(canSaveCoverEvent(CoverActionChoice.Position, canPosition = true))
        assertTrue(canSaveCoverEvent(CoverActionChoice.Open, canPosition = false))
        assertTrue(canSaveCoverEvent(CoverActionChoice.Close, canPosition = false))
    }

    @Test
    fun `limite - une plage exige deux places, un evenement une seule`() {
        assertTrue(canAddCoverEvent(9))
        assertFalse(canAddCoverEvent(10))
        assertTrue(canAddCoverWindow(8))
        assertFalse(canAddCoverWindow(9))
    }
}
