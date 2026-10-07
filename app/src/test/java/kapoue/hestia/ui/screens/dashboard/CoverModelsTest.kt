package kapoue.hestia.ui.screens.dashboard

import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.CoverStatusResult
import java.time.LocalDate
import java.time.LocalDateTime
import kapoue.hestia.R
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverModelsTest {
    @Test
    fun `etats connus et inconnus`() {
        assertEquals(CoverMotion.OPENING, coverMotionOf("opening"))
        assertEquals(CoverMotion.CALIBRATING, coverMotionOf("calibrating"))
        assertEquals(CoverMotion.UNKNOWN, coverMotionOf("weird"))
        assertEquals(CoverMotion.UNKNOWN, coverMotionOf(null))
    }

    @Test
    fun `defauts traduits sinon generique`() {
        assertEquals(CoverFault.OBSTRUCTION, coverFaultOf("obstruction"))
        assertEquals(CoverFault.VOLTAGE, coverFaultOf("undervoltage"))
        assertEquals(CoverFault.OTHER, coverFaultOf("cal_abort:timeout"))
    }

    @Test
    fun `statut en ligne et hors ligne`() {
        val s = RpcResult.Success(CoverStatusResult(id = 0, state = "stopped", currentPos = 40, posControl = true, errors = listOf("overpower"))).toCoverStatus()
        assertEquals(CoverStatus.Online(CoverMotion.STOPPED, 40, true, null, listOf(CoverFault.OVERPOWER)), s)
        assertEquals(CoverStatus.Offline, RpcResult.Failure(RpcFailure.TIMEOUT).toCoverStatus())
    }

    @Test
    fun `relevé rapide seulement en mouvement ou calibration`() {
        fun st(m: CoverMotion) = CoverStatus.Online(m, 50, true, null, emptyList())
        assertEquals(1_500L, coverPollIntervalMs(st(CoverMotion.CLOSING)))
        assertEquals(1_500L, coverPollIntervalMs(st(CoverMotion.CALIBRATING)))
        assertEquals(5_000L, coverPollIntervalMs(st(CoverMotion.STOPPED)))
        assertEquals(5_000L, coverPollIntervalMs(CoverStatus.Offline))
    }

    @Test
    fun `la position cible est reprise et bornee`() {
        val s = RpcResult.Success(CoverStatusResult(id = 0, state = "opening", currentPos = 20, targetPos = 140, posControl = true)).toCoverStatus()
        assertEquals(100, (s as CoverStatus.Online).target)
    }

    @Test
    fun `overcurrent est traduit en surpuissance`() {
        assertEquals(CoverFault.OVERPOWER, coverFaultOf("overcurrent"))
    }

    private val now = LocalDateTime.of(2026, 10, 7, 12, 0) // mercredi

    @Test
    fun `prochain evenement - choisit le plus proche`() {
        val events = listOf(
            CoverEvent(21, 0, action = CoverEventAction.Close),
            CoverEvent(15, 30, action = CoverEventAction.Open),
        )
        val next = nextCoverEvent(events, now)!!
        assertEquals(CoverEventAction.Open, next.action)
        assertEquals(LocalDateTime.of(2026, 10, 7, 15, 30), next.at)
        assertTrue(next.today)
    }

    @Test
    fun `prochain evenement - demain n'est pas aujourd'hui`() {
        val next = nextCoverEvent(listOf(CoverEvent(7, 30, action = CoverEventAction.Open)), now)!!
        assertEquals(LocalDateTime.of(2026, 10, 8, 7, 30), next.at)
        assertFalse(next.today)
    }

    @Test
    fun `prochain evenement - un evenement plus tard aujourd'hui l'emporte sur demain`() {
        val events = listOf(
            CoverEvent(7, 30, action = CoverEventAction.Open),
            CoverEvent(18, 0, action = CoverEventAction.Close),
        )
        val next = nextCoverEvent(events, now)!!
        assertEquals(CoverEventAction.Close, next.action)
        assertTrue(next.today)
    }

    @Test
    fun `prochain evenement - aucun evenement`() {
        assertNull(nextCoverEvent(emptyList(), now))
    }

    @Test
    fun `prochain evenement - uniques expires ignores`() {
        val past = CoverEvent(8, 0, date = LocalDate.of(2026, 10, 7), action = CoverEventAction.Open)
        assertNull(nextCoverEvent(listOf(past), now))
        val future = CoverEvent(9, 0, action = CoverEventAction.Close)
        assertEquals(CoverEventAction.Close, nextCoverEvent(listOf(past, future), now)!!.action)
    }

    @Test
    fun `libelle du prochain - cle selon l'action et le jour`() {
        val at = LocalDateTime.of(2026, 10, 7, 15, 0)
        assertEquals(R.string.cover_next_open_today, coverNextStringRes(NextCoverEvent(CoverEventAction.Open, at, true)))
        assertEquals(R.string.cover_next_open_day, coverNextStringRes(NextCoverEvent(CoverEventAction.Open, at, false)))
        assertEquals(R.string.cover_next_close_today, coverNextStringRes(NextCoverEvent(CoverEventAction.Close, at, true)))
        assertEquals(R.string.cover_next_close_day, coverNextStringRes(NextCoverEvent(CoverEventAction.Close, at, false)))
        assertEquals(R.string.cover_next_goto_today, coverNextStringRes(NextCoverEvent(CoverEventAction.GoTo(40), at, true)))
        assertEquals(R.string.cover_next_goto_day, coverNextStringRes(NextCoverEvent(CoverEventAction.GoTo(40), at, false)))
    }

    @Test
    fun `prochain evenement affiche seulement pour un volet en ligne hors calibration`() {
        fun online(m: CoverMotion) = CoverStatus.Online(m, 0, true, null, emptyList())
        assertTrue(coverShowsNext(online(CoverMotion.CLOSED)))
        assertTrue(coverShowsNext(online(CoverMotion.OPENING)))
        assertFalse(coverShowsNext(online(CoverMotion.CALIBRATING)))
        assertFalse(coverShowsNext(CoverStatus.Offline))
        assertFalse(coverShowsNext(CoverStatus.Loading))
    }

    @Test
    fun `prochain evenement - passage de minuit`() {
        val late = LocalDateTime.of(2026, 10, 7, 23, 50)
        val next = nextCoverEvent(listOf(CoverEvent(0, 10, action = CoverEventAction.Open)), late)!!
        assertEquals(LocalDateTime.of(2026, 10, 8, 0, 10), next.at)
        assertFalse(next.today)
    }

    @Test
    fun `prochain evenement - hebdomadaire le plus proche l'emporte`() {
        // now = mercredi 7 ; vendredi (5) avant dimanche (0) ; lundi (1) plus loin
        val events = listOf(
            CoverEvent(8, 0, setOf(0), action = CoverEventAction.Open),
            CoverEvent(8, 0, setOf(5), action = CoverEventAction.Close),
            CoverEvent(8, 0, setOf(1), action = CoverEventAction.Open),
        )
        val next = nextCoverEvent(events, now)!!
        assertEquals(CoverEventAction.Close, next.action)
        assertEquals(LocalDateTime.of(2026, 10, 9, 8, 0), next.at)
    }
}
