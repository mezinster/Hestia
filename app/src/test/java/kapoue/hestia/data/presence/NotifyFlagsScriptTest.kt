package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport.DURATION_MARKER
import kapoue.hestia.domain.model.PresenceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les générateurs de scripts n'émettent les appels ntfy que pour les notifications laissées actives. */
class NotifyFlagsScriptTest {

    private val window = PresenceWindow(8, 0, 9, 0, 0, setOf(1))

    private fun charge(nc: Boolean = true) = ChargeScriptGenerator.ChannelConfig(0, 5, 0, "Plug", "end", nc)
    private fun button(ne: Boolean = true, nc: Boolean = true) =
        ButtonTimerScriptGenerator.ChannelConfig(0, 600, 5, "Plug", notifyEnd = ne, notifyCutoff = nc)
    private fun presence(np: Boolean = true) = PresenceScriptGenerator.ChannelConfig(0, "Plug", listOf(window), np)

    private fun supervisor(c: ChargeScriptGenerator.ChannelConfig) =
        ChargeScriptGenerator.generateSupervisor(listOf(c), selfId = 3, ntfyTopic = "t", ntfyCutoffBody = "$DURATION_MARKER x")
    private fun buttonScript(c: ButtonTimerScriptGenerator.ChannelConfig) =
        ButtonTimerScriptGenerator.generate(listOf(c), ntfyTopic = "t", ntfyEndBody = "e", ntfyCutoffBody = "$DURATION_MARKER x")
    private fun presenceScript(c: PresenceScriptGenerator.ChannelConfig) =
        PresenceScriptGenerator.generateSupervisor(listOf(c), ntfyTopic = "t", ntfyStartBody = "s", ntfyEndBody = "e")

    @Test
    fun `default config emits no flags`() {
        val all = listOf(
            supervisor(charge()), ChargeScriptGenerator.evalUpsertChannel(charge()),
            buttonScript(button()), ButtonTimerScriptGenerator.evalUpsertChannel(button()),
            presenceScript(presence()), PresenceScriptGenerator.evalUpsertChannel(presence()),
        )
        for (code in all) {
            assertFalse(code, code.contains(", nc: false"))
            assertFalse(code, code.contains(", ne: false"))
            assertFalse(code, code.contains(", np: false"))
        }
    }

    @Test
    fun `disabled flags appear in the channel literal`() {
        assertTrue(supervisor(charge(false)).contains(", nc: false"))
        assertTrue(ChargeScriptGenerator.evalUpsertChannel(charge(false)).contains(", nc: false"))
        val b = button(ne = false, nc = false)
        for (code in listOf(buttonScript(b), ButtonTimerScriptGenerator.evalUpsertChannel(b))) {
            assertTrue(code.contains(", ne: false"))
            assertTrue(code.contains(", nc: false"))
        }
        assertTrue(presenceScript(presence(false)).contains(", np: false"))
        assertTrue(PresenceScriptGenerator.evalUpsertChannel(presence(false)).contains(", np: false"))
    }

    @Test
    fun `flags survive a live snapshot round trip`() {
        val cfgJson = """{"cfg":[{"switchId":0,"name":"Plug","windows":[[480,540,0,[1]]],"np":false}],"state":[{}]}"""
        assertFalse(PresenceScriptGenerator.parseLiveSnapshot(cfgJson)!!.first[0].notify)
        val bJson = """{"cfg":[{"switchId":0,"name":"Plug","durationSec":600,"thresholdW":5,"ne":false}],"state":[{}]}"""
        val b = ButtonTimerScriptGenerator.parseLiveSnapshot(bJson)!!.first[0]
        assertFalse(b.notifyEnd)
        assertTrue(b.notifyCutoff)
        val full = ChargeScriptGenerator.parseFullEvalResult("""[[0,5,0,"Plug","end",false]]""")
        assertFalse(full[0].notifyCutoff)
        assertTrue(ChargeScriptGenerator.parseFullEvalResult("""[[0,5,0,"Plug","end"]]""")[0].notifyCutoff)
    }

    @Test
    fun `cutoff off keeps the switch-off`() {
        for (flag in listOf(true, false)) {
            val code = ChargeScriptGenerator.generate(
                switchId = 0, thresholdW = 5, belowSec = 60, selfId = 7,
                ntfyTopic = "t", ntfyTitle = "Plug", ntfyBody = "$DURATION_MARKER x", notifyCutoff = flag,
            )
            assertTrue(code.contains("Switch.Set\", { id: CFG.switchId, on: false }"))
            assertEquals(flag, code.contains("fmtDur(now - onSince) + "))
        }
    }

    @Test
    fun `guards are in the generated code`() {
        assertTrue(supervisor(charge()).contains("cfg.nc !== false"))
        val b = buttonScript(button())
        assertTrue(b.contains("cfg.ne !== false"))
        assertTrue(b.contains("cfg.nc !== false"))
        assertTrue(presenceScript(presence()).contains("cfg.np !== false"))
    }

    @Test
    fun `empty end body means no end notification`() {
        assertTrue(supervisor(charge()).contains("if (body === \"\") return;"))
    }

    @Test
    fun `blank end body emits no end ntfy call`() {
        val code = ChargeScriptGenerator.generate(
            switchId = 0, thresholdW = 5, belowSec = 60, selfId = 7,
            ntfyTopic = "t", ntfyTitle = "Plug", ntfyBody = "$DURATION_MARKER x", notifyCutoff = false,
        )
        assertFalse(code, code.contains("HTTP.Request"))
        val withEnd = ChargeScriptGenerator.generate(
            switchId = 0, thresholdW = 5, belowSec = 60, selfId = 7,
            ntfyTopic = "t", ntfyTitle = "Plug", ntfyEndBody = "fin", notifyCutoff = false,
        )
        assertTrue(withEnd.contains("body: \"fin\""))
    }

    @Test
    fun `notif versions bumped`() {
        assertEquals(3, ChargeScriptGenerator.parseNotifVersion(supervisor(charge())))
        assertEquals(3, ChargeScriptGenerator.parseNotifVersion(
            ChargeScriptGenerator.generate(0, 5, 60, 7, ntfyTopic = "t", ntfyBody = "x")))
        assertEquals(3, ButtonTimerScriptGenerator.parseNotifVersion(buttonScript(button())))
    }
}
