package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.presence.ButtonTimerScriptGenerator
import kapoue.hestia.data.presence.ChargeScriptGenerator
import kapoue.hestia.data.presence.PresenceScriptGenerator
import kapoue.hestia.domain.model.PresenceWindow
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.NotifyKind
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.notifies
import kapoue.hestia.domain.model.withKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Choix des notifications de planning d'après le masque du canal. */
class NotifyRulesTest {

    private fun plug(mask: Int, switchId: Int = 0) =
        Device(name = "Plug", ipAddress = "192.168.1.96", switchId = switchId, type = DeviceType.PLUG, notifyMask = mask)

    private val window = PresenceWindow(8, 0, 9, 0, 0, setOf(1))

    @Test
    fun `precise planning follows start and end bits`() {
        val mask = NotifyKind.ALL.withKind(NotifyKind.PLANNING_END, false)
        assertEquals(PlanningNotify(start = true, end = false), planningNotify(plug(mask), isPresence = false))
        val noStart = NotifyKind.ALL.withKind(NotifyKind.PLANNING_START, false)
        assertEquals(PlanningNotify(start = false, end = true), planningNotify(plug(noStart), isPresence = false))
    }

    @Test
    fun `presence follows PRESENCE`() {
        val presenceOff = NotifyKind.ALL.withKind(NotifyKind.PRESENCE, false)
        assertEquals(PlanningNotify(false, false), planningNotify(plug(presenceOff), isPresence = true))
        val planningOff = NotifyKind.ALL
            .withKind(NotifyKind.PLANNING_START, false)
            .withKind(NotifyKind.PLANNING_END, false)
        assertEquals(PlanningNotify(true, true), planningNotify(plug(planningOff), isPresence = true))
    }

    @Test
    fun `button configs take each channel's row, snapshot kept without a row`() {
        // Script pré-v3 : aucun drapeau, tout à vrai à la relecture.
        val configs = listOf(0, 1, 2).map { ButtonTimerScriptGenerator.ChannelConfig(it, 600, 5, "C$it") }
        val rows = listOf(
            plug(NotifyKind.ALL.withKind(NotifyKind.TIMER_END, false), switchId = 0),
            plug(NotifyKind.ALL.withKind(NotifyKind.CUTOFF, false), switchId = 1),
        )
        val out = applyMaskToButtonConfigs(configs, rows)
        assertFalse(out[0].notifyEnd)
        assertTrue(out[0].notifyCutoff)
        assertTrue(out[1].notifyEnd)
        assertFalse(out[1].notifyCutoff)
        assertEquals(configs[2], out[2])
        assertEquals(configs, applyMaskToButtonConfigs(configs, emptyList()))
    }

    @Test
    fun `presence configs take each channel's row`() {
        val configs = listOf(0, 1).map { PresenceScriptGenerator.ChannelConfig(it, "C$it", listOf(window)) }
        val rows = listOf(plug(NotifyKind.ALL.withKind(NotifyKind.PRESENCE, false), switchId = 1))
        val out = applyMaskToPresenceConfigs(configs, rows)
        assertTrue(out[0].notify)
        assertFalse(out[1].notify)
    }

    @Test
    fun `charge configs drop end body and cutoff per row`() {
        val configs = listOf(0, 1, 2).map { ChargeScriptGenerator.ChannelConfig(it, 5, 0, "C$it", "fin") }
        val rows = listOf(
            plug(NotifyKind.ALL.withKind(NotifyKind.TIMER_END, false), switchId = 0),
            plug(NotifyKind.ALL.withKind(NotifyKind.CUTOFF, false), switchId = 1),
        )
        val out = applyMaskToChargeConfigs(configs, rows)
        assertEquals("", out[0].endBody)
        assertTrue(out[0].notifyCutoff)
        assertEquals("fin", out[1].endBody)
        assertFalse(out[1].notifyCutoff)
        assertEquals(configs[2], out[2])
    }

    private fun inSlot(plannings: List<Planning>, now: LocalDateTime) =
        splitThresholdInSlot(plannings, now).inSlot.isNotEmpty()

    @Test
    fun `only the running threshold planning is held back`() {
        val everyDay = (0..6).toSet()
        val running = Planning(8, 0, 10, 0, everyDay, onJobId = 1, cutoffScriptId = 3, cutoffThresholdW = 5)
        val later = Planning(18, 0, 20, 0, everyDay, onJobId = 2, cutoffScriptId = 4, cutoffThresholdW = 5)
        val plain = Planning(8, 0, 10, 0, everyDay, onJobId = 5)
        val presence = Planning(8, 0, 10, 0, everyDay, cutoffThresholdW = null, marginMinutes = 15)
        val split = splitThresholdInSlot(listOf(running, later, plain, presence), LocalDateTime.of(2026, 10, 29, 9, 0))
        assertEquals(listOf(running), split.inSlot)
        assertEquals(listOf(later, plain, presence), split.rewriteNow)
    }

    @Test
    fun `threshold planning in its slot blocks the resync`() {
        val everyDay = (0..6).toSet()
        val threshold = Planning(8, 0, 10, 0, everyDay, cutoffScriptId = 3, cutoffThresholdW = 5)
        val nine = LocalDateTime.of(2026, 10, 29, 9, 0)
        val eleven = LocalDateTime.of(2026, 10, 29, 11, 0)
        assertTrue(inSlot(listOf(threshold), nine))
        assertFalse(inSlot(listOf(threshold), eleven))
        assertFalse(inSlot(listOf(threshold.copy(cutoffScriptId = null, cutoffThresholdW = null)), nine))
        val presence = Planning(8, 0, 10, 0, everyDay, cutoffThresholdW = null, marginMinutes = 15)
        assertFalse(inSlot(listOf(presence), nine))
    }

    @Test
    fun `one-shot threshold planning blocks only on its date`() {
        val day = java.time.LocalDate.of(2026, 10, 29)
        val once = Planning(8, 0, 10, 0, emptySet(), date = day, cutoffScriptId = 3, cutoffThresholdW = 5)
        assertTrue(inSlot(listOf(once), day.atTime(9, 0)))
        assertFalse(inSlot(listOf(once), day.plusDays(1).atTime(9, 0)))
        assertFalse(inSlot(listOf(once), day.atTime(10, 0)))
    }

    @Test
    fun `threshold slot crossing midnight blocks on both sides`() {
        // Jeudi seulement (cron 4) : 22:00 → 02:00 le vendredi.
        val night = Planning(22, 0, 2, 0, setOf(4), cutoffScriptId = 3, cutoffThresholdW = 5)
        val thursday = LocalDateTime.of(2026, 10, 29, 23, 0)
        assertTrue(inSlot(listOf(night), thursday))
        assertTrue(inSlot(listOf(night), thursday.plusHours(2))) // vendredi 01:00
        assertFalse(inSlot(listOf(night), thursday.plusHours(4))) // vendredi 03:00
        assertFalse(inSlot(listOf(night), thursday.minusHours(2))) // jeudi 21:00
    }

    @Test
    fun `local tags map to kinds`() {
        assertEquals(NotifyKind.PLANNING_START, localNotifyKind("pl-start"))
        assertEquals(NotifyKind.PLANNING_END, localNotifyKind("pl-end"))
        assertEquals(NotifyKind.PRESENCE, localNotifyKind("pr-start"))
        assertEquals(NotifyKind.PRESENCE, localNotifyKind("pr-end"))
        assertEquals(NotifyKind.CUTOFF, localNotifyKind("cut"))
        assertEquals(NotifyKind.TIMER_END, localNotifyKind("timer-end"))
        assertEquals(NotifyKind.COVER_MOVES, localNotifyKind("cv-open"))
        assertEquals(NotifyKind.COVER_MOVES, localNotifyKind("cv-close"))
        assertEquals(NotifyKind.COVER_MOVES, localNotifyKind("cv-goto"))
        assertNull(localNotifyKind("smoke"))
    }

    @Test
    fun `presence boundaries follow PRESENCE`() {
        val device = plug(NotifyKind.ALL.withKind(NotifyKind.PRESENCE, false))
        assertEquals(NotifyKind.PRESENCE, localNotifyKind("pr-start"))
        assertFalse(device.notifies(localNotifyKind("pr-start")!!))
        assertTrue(device.notifies(localNotifyKind("pl-start")!!))
    }

    @Test
    fun `ntfy catch-up retries only an unreachable device`() {
        assertTrue(NtfyResyncOutcome.DONE.countsAsSynced())
        // Échec persistant d'une réécriture : jamais retenté à chaque relevé du Tableau.
        assertTrue(NtfyResyncOutcome.FAILED.countsAsSynced())
        assertFalse(NtfyResyncOutcome.UNREACHABLE.countsAsSynced())
        // Planning avec seuil en cours : le reste est réécrit, lui attend le prochain lancement
        // (canal marqué en attente) — pas de rattrapage à chaque relevé.
        assertTrue(NtfyResyncOutcome.PARTIAL_IN_SLOT.countsAsSynced())
    }
}
