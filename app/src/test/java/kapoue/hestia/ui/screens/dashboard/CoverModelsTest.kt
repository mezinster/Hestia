package kapoue.hestia.ui.screens.dashboard

import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.CoverStatusResult
import org.junit.Assert.assertEquals
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
}
