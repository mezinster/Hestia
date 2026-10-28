package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport.DURATION_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Texte de la notif de coupure (durée en tête) et marqueurs de version des scripts générés. */
class CutoffScriptTextTest {

    private val template = "$DURATION_MARKER of charging — switched off"

    private fun planningScript(ntfyBody: String = template) = ChargeScriptGenerator.generate(
        switchId = 0, thresholdW = 5, belowSec = 60, selfId = 7,
        ntfyTopic = "topic", ntfyTitle = "Plug", ntfyBody = ntfyBody, planEndBody = "Planning ended",
    )

    @Test
    fun `planning cutoff script puts the duration first`() {
        assertTrue(planningScript().contains("fmtDur(now - onSince) + \" of charging — switched off\""))
    }

    @Test
    fun `planning cutoff script carries the current text version`() {
        assertEquals(ChargeScriptGenerator.NOTIF_VERSION, ChargeScriptGenerator.parseNotifVersion(planningScript()))
    }

    @Test
    fun `text version moved past the pre-duration-first format`() {
        assertTrue(ChargeScriptGenerator.NOTIF_VERSION >= 2)
        assertTrue(ButtonTimerScriptGenerator.NOTIF_VERSION >= 2)
    }

    @Test
    fun `a generated planning script is not legacy`() {
        assertFalse(ChargeScriptGenerator.lacksPlanEnd(planningScript()))
    }

    @Test
    fun `a script without planEnd is legacy`() {
        val pre216 = planningScript().replace("function planEnd(", "function somethingElse(")
        assertTrue(ChargeScriptGenerator.lacksPlanEnd(pre216))
    }

    @Test
    fun `supervisor script puts the duration first`() {
        val code = ChargeScriptGenerator.generateSupervisor(
            listOf(ChargeScriptGenerator.ChannelConfig(0, 5, 0, "Plug", "")),
            selfId = 3, ntfyTopic = "topic", ntfyCutoffBody = template,
        )
        assertTrue(code.contains("fmtDur(durationSec) + \" of charging — switched off\""))
    }

    @Test
    fun `button timer script puts the duration first`() {
        val code = ButtonTimerScriptGenerator.generate(
            listOf(ButtonTimerScriptGenerator.ChannelConfig(0, 600, 5, "Plug")),
            ntfyTopic = "topic", ntfyCutoffBody = template,
        )
        assertTrue(code.contains("fmtDur(durationSec) + \" of charging — switched off\""))
    }
}
