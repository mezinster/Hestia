package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.presence.ButtonTimerScriptGenerator
import kapoue.hestia.data.presence.ChargeScriptGenerator
import kapoue.hestia.data.presence.PresenceScriptGenerator
import kapoue.hestia.domain.model.PresenceWindow
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.NotifyKind
import kapoue.hestia.domain.model.withKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
