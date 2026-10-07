package kapoue.hestia.data.repository

import java.time.LocalDate
import java.time.LocalDateTime
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import kapoue.hestia.data.rpc.NtfyTexts
import kapoue.hestia.data.rpc.coverActionCall
import kapoue.hestia.data.rpc.coverNtfyCall
import kapoue.hestia.data.rpc.model.ScheduleCall
import kapoue.hestia.data.rpc.model.ScheduleJob
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kapoue.hestia.data.rpc.coverTimespec
import org.junit.Test

/** Règles de validation avant création d'événements de volet (lot S2, 2026-10-07). */
class CoverScheduleRulesTest {
    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)

    private fun ev(h: Int, m: Int = 0, days: Set<Int> = emptySet(), date: LocalDate? = null) =
        CoverEvent(h, m, days, date, CoverEventAction.Open, jobId = 1)

    @Test
    fun `un unique dans le passe est refuse`() {
        val past = ev(8, date = LocalDate.of(2026, 10, 7))
        assertEquals(CoverEventResult.PastOnce, validateNewEvents(emptyList(), listOf(past), now))
    }

    @Test
    fun `un unique dans le futur est accepte`() {
        assertNull(validateNewEvents(emptyList(), listOf(ev(18, date = LocalDate.of(2026, 10, 7))), now))
    }

    @Test
    fun `meme creneau qu'un existant est un doublon`() {
        assertEquals(CoverEventResult.Duplicate, validateNewEvents(listOf(ev(7)), listOf(ev(7)), now))
    }

    @Test
    fun `deux nouveaux sur le meme creneau sont un doublon`() {
        assertEquals(CoverEventResult.Duplicate, validateNewEvents(emptyList(), listOf(ev(7), ev(7)), now))
    }

    @Test
    fun `neuf existants plus une plage depassent la limite`() {
        val existing = (0..8).map { ev(it) }
        assertEquals(CoverEventResult.LimitReached, validateNewEvents(existing, listOf(ev(10), ev(11)), now))
    }

    @Test
    fun `huit existants plus une plage tiennent`() {
        val existing = (0..7).map { ev(it) }
        assertNull(validateNewEvents(existing, listOf(ev(10), ev(11)), now))
    }

    @Test
    fun `modifier un evenement sur son propre creneau est accepte`() {
        val old = ev(7)
        assertNull(validateNewEvents(listOf(old), listOf(ev(7)), now, ignoring = old))
    }

    @Test
    fun `modifier ne compte pas l'ancien dans la limite`() {
        val existing = (0..9).map { ev(it) }
        assertNull(validateNewEvents(existing, listOf(ev(20)), now, ignoring = existing[0]))
    }

    @Test
    fun `ignorer un evenement absent ne libere pas de place`() {
        val existing = (0..9).map { ev(it) }
        assertEquals(CoverEventResult.LimitReached, validateNewEvents(existing, listOf(ev(20)), now, ignoring = ev(22)))
    }

    private fun call(m: JsonObject) = ScheduleCall(m["method"]!!.jsonPrimitive.content, m["params"]!!.jsonObject)
    private val action = coverActionCall(0, CoverEventAction.Open)
    private fun ntfy(topic: String = "t", title: String = "Volet") = coverNtfyCall(NtfyTexts(topic, title, "Ouvert"))
    private fun job(vararg calls: JsonObject) = ScheduleJob(1, true, "0 0 7 * * *", calls.map { call(it) })

    @Test
    fun `ntfy desactive avec un appel HTTP a retirer`() =
        assertTrue(coverJobNeedsNtfyRewrite(job(action, ntfy()), null))

    @Test
    fun `ntfy desactive sans appel HTTP rien a faire`() =
        assertFalse(coverJobNeedsNtfyRewrite(job(action), null))

    @Test
    fun `ntfy actif avec le meme appel rien a faire`() =
        assertFalse(coverJobNeedsNtfyRewrite(job(action, ntfy()), ntfy()))

    @Test
    fun `ntfy actif sujet ou titre different a reecrire`() {
        assertTrue(coverJobNeedsNtfyRewrite(job(action, ntfy()), ntfy(topic = "autre")))
        assertTrue(coverJobNeedsNtfyRewrite(job(action, ntfy()), ntfy(title = "Salon")))
    }

    @Test
    fun `ntfy actif sans appel HTTP a ajouter`() =
        assertTrue(coverJobNeedsNtfyRewrite(job(action), ntfy()))

    // --- Plan de resynchronisation ntfy ---
    private fun jobAt(id: Int, hour: Int, topic: String?) = ScheduleJob(
        id, true, coverTimespec(ev(hour)),
        listOfNotNull(action, topic?.let { ntfy(topic = it) }).map { call(it) },
    )
    private val expectedOn: (CoverEventAction) -> JsonObject? = { ntfy() }

    @Test
    fun `perime avec copie correcte du meme creneau est supprime seul`() {
        val plan = coverNtfyResyncPlan(listOf(jobAt(1, 7, "ancien"), jobAt(2, 7, "t")), 0, expectedOn)
        assertEquals(NtfyResyncStep.DeleteOnly, plan[1]); assertEquals(NtfyResyncStep.Keep, plan[2])
    }

    @Test
    fun `perime seul est reecrit`() {
        assertEquals(NtfyResyncStep.Rewrite, coverNtfyResyncPlan(listOf(jobAt(1, 7, "ancien")), 0, expectedOn)[1])
    }

    @Test
    fun `tout correct est conserve`() {
        val plan = coverNtfyResyncPlan(listOf(jobAt(1, 7, "t"), jobAt(2, 8, "t")), 0, expectedOn)
        assertTrue(plan.values.all { it == NtfyResyncStep.Keep })
    }

    @Test
    fun `perime d'un creneau et correct d'un autre creneau est reecrit`() {
        val plan = coverNtfyResyncPlan(listOf(jobAt(1, 7, "ancien"), jobAt(2, 8, "t")), 0, expectedOn)
        assertEquals(NtfyResyncStep.Rewrite, plan[1]); assertEquals(NtfyResyncStep.Keep, plan[2])
    }
}
