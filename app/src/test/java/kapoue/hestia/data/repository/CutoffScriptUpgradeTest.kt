package kapoue.hestia.data.repository

import kapoue.hestia.data.presence.ChargeScriptGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

class CutoffScriptUpgradeTest {

    private val current = ChargeScriptGenerator.generate(
        switchId = 0, thresholdW = 5, belowSec = 60, selfId = 7,
        ntfyTopic = "topic", ntfyTitle = "Plug", ntfyBody = "x", planEndBody = "Planning ended",
    )

    // Script de la 2.16.x : planEnd() présent, mais aucun marqueur de version (texte « …après 1h14 »).
    private val staleText = current.lineSequence().filterNot { it.contains("hestia_notif_ver:") }.joinToString("\n")

    // Script antérieur à planEnd() (avant upstream 2.16.0).
    private val legacy = staleText.replace("function planEnd(", "function other(")

    @Test
    fun `an up-to-date script is left alone`() {
        assertEquals(CutoffScriptUpgrade.NONE, CutoffScriptUpgrade.decide(current, activeNow = false))
    }

    @Test
    fun `an up-to-date script running now is left alone too`() {
        assertEquals(CutoffScriptUpgrade.NONE, CutoffScriptUpgrade.decide(current, activeNow = true))
    }

    @Test
    fun `a script with stale text only is rewritten in place`() {
        assertEquals(CutoffScriptUpgrade.REWRITE_TEXT, CutoffScriptUpgrade.decide(staleText, activeNow = false))
    }

    @Test
    fun `a script without planEnd gets its planning re-saved`() {
        assertEquals(CutoffScriptUpgrade.RESAVE, CutoffScriptUpgrade.decide(legacy, activeNow = false))
    }

    @Test
    fun `a stale script of a running planning is postponed`() {
        assertEquals(CutoffScriptUpgrade.POSTPONE, CutoffScriptUpgrade.decide(staleText, activeNow = true))
        assertEquals(CutoffScriptUpgrade.POSTPONE, CutoffScriptUpgrade.decide(legacy, activeNow = true))
    }
}
