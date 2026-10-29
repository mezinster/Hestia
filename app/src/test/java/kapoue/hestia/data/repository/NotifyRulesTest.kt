package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.NotifyKind
import kapoue.hestia.domain.model.withKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Choix des notifications de planning d'après le masque du canal. */
class NotifyRulesTest {

    private fun plug(mask: Int) = Device(name = "Plug", ipAddress = "192.168.1.96", type = DeviceType.PLUG, notifyMask = mask)

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
}
