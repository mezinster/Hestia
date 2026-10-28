package kapoue.hestia.data.notifications

import kapoue.hestia.data.notifications.NtfyScriptSupport.DURATION_MARKER
import org.junit.Assert.assertEquals
import org.junit.Test

class NtfyScriptSupportTest {

    @Test
    fun `duration first when the marker opens the sentence`() {
        assertEquals(
            "fmtDur(d) + \" of charging — switched off\"",
            NtfyScriptSupport.bodyWithDuration("$DURATION_MARKER of charging — switched off", "fmtDur(d)"),
        )
    }

    @Test
    fun `duration in the middle keeps both sides`() {
        assertEquals(
            "\"Après \" + fmtDur(d) + \" de charge\"",
            NtfyScriptSupport.bodyWithDuration("Après $DURATION_MARKER de charge", "fmtDur(d)"),
        )
    }

    @Test
    fun `duration last when the marker closes the sentence`() {
        assertEquals(
            "\"Отключено через \" + fmtDur(d)",
            NtfyScriptSupport.bodyWithDuration("Отключено через $DURATION_MARKER", "fmtDur(d)"),
        )
    }

    @Test
    fun `a template without marker gets the duration appended as before`() {
        assertEquals(
            "\"Switched off after \" + fmtDur(d)",
            NtfyScriptSupport.bodyWithDuration("Switched off after", "fmtDur(d)"),
        )
    }

    @Test
    fun `text around the marker is escaped for JS`() {
        assertEquals(
            "fmtDur(d) + \" de \\\"charge\\\"\"",
            NtfyScriptSupport.bodyWithDuration("$DURATION_MARKER de \"charge\"", "fmtDur(d)"),
        )
    }
}
