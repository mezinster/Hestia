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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    // Appel tiers en LAN (webhook domotique) : jamais touché par la resynchronisation ntfy.
    private val lanHook = buildJsonObject {
        put("method", "HTTP.Request")
        put("params", buildJsonObject { put("method", "GET"); put("url", "http://192.168.1.10/hook") })
    }

    @Test
    fun `appel HTTP tiers conserve ntfy desactive`() {
        assertNull(coverResyncCalls(job(action, lanHook), null))
        assertEquals(listOf(action, lanHook), coverResyncCalls(job(action, lanHook, ntfy()), null))
    }

    @Test
    fun `appel HTTP tiers conserve ntfy actif, ntfy ajoute apres lui`() {
        assertEquals(listOf(action, lanHook, ntfy()), coverResyncCalls(job(action, lanHook), ntfy()))
    }

    @Test
    fun `appel ntfy perime remplace`() {
        assertEquals(listOf(action, ntfy()), coverResyncCalls(job(action, ntfy(topic = "ancien")), ntfy()))
        assertEquals(listOf(action, ntfy()), coverResyncCalls(job(action, ntfy(title = "Salon")), ntfy()))
    }

    @Test
    fun `ntfy desactive retire seulement l'appel ntfy`() {
        assertEquals(listOf(action), coverResyncCalls(job(action, ntfy()), null))
    }

    @Test
    fun `job deja conforme rien a mettre a jour`() {
        assertNull(coverResyncCalls(job(action, ntfy()), ntfy()))
        assertNull(coverResyncCalls(job(action), null))
    }

    @Test
    fun `ntfy actif sans appel ntfy ajoute`() {
        assertEquals(listOf(action, ntfy()), coverResyncCalls(job(action), ntfy()))
    }

    @Test
    fun `seul un HTTP Request vers ntfy sh compte comme ntfy`() {
        assertTrue(isCoverNtfyCall(call(ntfy())))
        assertFalse(isCoverNtfyCall(call(lanHook)))
        assertFalse(isCoverNtfyCall(call(action)))
    }

    @Test
    fun `sept jours coches en doublon de tous les jours`() {
        val existing = CoverEvent(21, 0, emptySet(), null, CoverEventAction.Close, jobId = 1)
        val new = CoverEvent(21, 0, (0..6).toSet(), null, CoverEventAction.Open)
        assertEquals(CoverEventResult.Duplicate, validateNewEvents(listOf(existing), listOf(new), now))
    }
}
