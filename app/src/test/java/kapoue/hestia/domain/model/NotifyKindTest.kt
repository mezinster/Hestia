package kapoue.hestia.domain.model

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.NotifyKind.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyKindTest {

    private fun device(
        type: DeviceType = DeviceType.PLUG,
        scripting: Boolean = false,
        metering: Boolean = false,
        light: Boolean = false,
        cover: Boolean = false,
        switch: Boolean = true,
        mask: Int = NotifyKind.ALL,
    ) = Device(
        name = "d", ipAddress = "1.2.3.4", type = type, hasScripting = scripting,
        hasPowerMetering = metering, isLight = light, isCover = cover,
        supportsSwitch = switch, notifyMask = mask,
    )

    @Test
    fun `ALL covers every kind`() {
        assertTrue(NotifyKind.entries.all { NotifyKind.ALL.allows(it) })
        assertEquals(63, NotifyKind.entries.sumOf { it.bit })
    }

    @Test
    fun `withKind toggles one bit only`() {
        assertEquals(59, 63.withKind(CUTOFF, false))
        assertEquals(63, 59.withKind(CUTOFF, true))
        assertTrue(59.allows(TIMER_END))
    }

    @Test
    fun `applicable kinds per device kind`() {
        assertEquals(
            listOf(PLANNING_START, PLANNING_END, CUTOFF, TIMER_END, PRESENCE),
            applicableKinds(device(scripting = true, metering = true)),
        )
        assertEquals(
            listOf(PLANNING_START, PLANNING_END, TIMER_END),
            applicableKinds(device()),
        )
        assertEquals(
            listOf(PLANNING_START, PLANNING_END),
            applicableKinds(device(type = DeviceType.LAMP, light = true, switch = false)),
        )
        assertEquals(
            listOf(COVER_MOVES),
            applicableKinds(device(type = DeviceType.SHUTTER, cover = true, switch = false)),
        )
        assertEquals(
            emptyList<NotifyKind>(),
            applicableKinds(device(type = DeviceType.SMOKE_DETECTOR, switch = false)),
        )
    }

    @Test
    fun `smoke detector always notifies`() {
        val smoke = device(type = DeviceType.SMOKE_DETECTOR, switch = false, mask = 0)
        assertTrue(smoke.notifies(PLANNING_START))
    }
}
