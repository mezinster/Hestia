package kapoue.hestia.data.rpc

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleCodecTest {

    @Test
    fun `resolveYear keeps a Unique of earlier this week in the current year`() {
        // Cas réel : Unique du mardi 06/10/2026, relu le lendemain — il était lu 06/10/2027.
        val today = LocalDate.of(2026, 10, 7)
        assertEquals(LocalDate.of(2026, 10, 6), ScheduleCodec.resolveYear(10, 6, today))
    }

    @Test
    fun `resolveYear keeps a Unique still to come in the current year`() {
        val today = LocalDate.of(2026, 10, 7)
        assertEquals(LocalDate.of(2026, 10, 12), ScheduleCodec.resolveYear(10, 12, today))
    }

    @Test
    fun `resolveYear reads a late-December Unique for early January as next year`() {
        val today = LocalDate.of(2026, 12, 29)
        assertEquals(LocalDate.of(2027, 1, 3), ScheduleCodec.resolveYear(1, 3, today))
    }

    @Test
    fun `resolveYear reads a late-December Unique seen in early January as last year`() {
        val today = LocalDate.of(2027, 1, 2)
        assertEquals(LocalDate.of(2026, 12, 30), ScheduleCodec.resolveYear(12, 30, today))
    }

    @Test
    fun `resolveYear returns null for 29 February without a nearby leap year`() {
        val today = LocalDate.of(2026, 3, 1)
        assertNull(ScheduleCodec.resolveYear(2, 29, today))
    }

    @Test
    fun `resolveYear picks the nearby leap year for 29 February`() {
        val today = LocalDate.of(2028, 3, 2)
        assertEquals(LocalDate.of(2028, 2, 29), ScheduleCodec.resolveYear(2, 29, today))
    }
}
