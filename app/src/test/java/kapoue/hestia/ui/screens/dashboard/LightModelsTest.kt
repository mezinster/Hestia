package kapoue.hestia.ui.screens.dashboard

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kapoue.hestia.domain.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightModelsTest {

    @Test
    fun `un statut lu devient Online avec luminosite arrondie`() {
        val status = RpcResult.Success(LightStatusResult(id = 0, output = true, brightness = 39.6, apower = 5.0)).toLightStatus()
        assertEquals(LightStatus.Online(on = true, brightness = 40, powerWatts = 5.0), status)
    }

    @Test
    fun `une luminosite absente vaut 100`() {
        val status = RpcResult.Success(LightStatusResult(id = 0, output = false)).toLightStatus()
        assertEquals(LightStatus.Online(on = false, brightness = 100, powerWatts = null), status)
    }

    @Test
    fun `un echec devient Offline`() {
        assertEquals(LightStatus.Offline, RpcResult.Failure(RpcFailure.TIMEOUT).toLightStatus())
    }

    @Test
    fun `la cle de groupe distingue relais et lights d'une meme adresse`() {
        val l = Device(name = "L", ipAddress = "1.2.3.4", type = DeviceType.LAMP, isLight = true)
        val p = Device(name = "P", ipAddress = "1.2.3.4", type = DeviceType.PLUG)
        assertEquals("1.2.3.4" to ChannelKind.LIGHT, l.groupKey())
        assertEquals("1.2.3.4" to ChannelKind.RELAY, p.groupKey())
    }

    private fun cover(ip: String, id: Int) = Device(name = "C$id", ipAddress = ip, switchId = id, type = DeviceType.SHUTTER, isCover = true, supportsSwitch = false)

    @Test
    fun `deux volets d'une meme IP gardent chacun leur tuile`() {
        val flags = computeGroupFlags(listOf(cover("1.1.1.1", 0), cover("1.1.1.1", 1)))
        assertEquals(listOf(GroupFlags(true, false), GroupFlags(false, false)), flags)
    }

    @Test
    fun `relais et volet d'une meme IP ne se melangent pas`() {
        val r0 = Device(name = "R0", ipAddress = "1.1.1.1", switchId = 0, type = DeviceType.PLUG)
        val r1 = Device(name = "R1", ipAddress = "1.1.1.1", switchId = 1, type = DeviceType.PLUG)
        val flags = computeGroupFlags(listOf(r0, cover("1.1.1.1", 0), r1))
        assertEquals(listOf(GroupFlags(true, true), GroupFlags(true, false), GroupFlags(false, true)), flags)
    }

    private fun plug(id: Int, ip: String = "1.2.3.4") = Device(name = "P$id", ipAddress = ip, switchId = id, type = DeviceType.PLUG)
    private fun light(ip: String = "1.2.3.4") = Device(name = "L", ipAddress = ip, switchId = 0, type = DeviceType.LAMP, isLight = true)

    @Test
    fun `appareil mixte avec le light en premier garde le bandeau des relais`() {
        val flags = computeGroupFlags(listOf(light(), plug(0), plug(1)))
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = false), flags[0])
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = true), flags[1])
        assertEquals(GroupFlags(isFirstInGroup = false, isMultiChannel = true), flags[2])
    }

    @Test
    fun `appareil mixte avec le light en dernier garde le bandeau des relais`() {
        val flags = computeGroupFlags(listOf(plug(0), plug(1), light()))
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = true), flags[0])
        assertEquals(GroupFlags(isFirstInGroup = false, isMultiChannel = true), flags[1])
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = false), flags[2])
    }

    @Test
    fun `un relais seul avec un light reste une tuile simple`() {
        val flags = computeGroupFlags(listOf(plug(0), light()))
        assertFalse(flags[0].isMultiChannel)
        assertFalse(flags[1].isMultiChannel)
    }

    @Test
    fun `quatre lights de meme adresse forment un seul bandeau`() {
        val flags = computeGroupFlags(List(4) { light() })
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = true), flags[0])
        (1..3).forEach { assertEquals(GroupFlags(isFirstInGroup = false, isMultiChannel = true), flags[it]) }
    }

    @Test
    fun `relais et lights d'une meme adresse forment deux groupes independants`() {
        val flags = computeGroupFlags(listOf(plug(0), plug(1), light(), light()))
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = true), flags[0])
        assertEquals(GroupFlags(isFirstInGroup = false, isMultiChannel = true), flags[1])
        assertEquals(GroupFlags(isFirstInGroup = true, isMultiChannel = true), flags[2])
        assertEquals(GroupFlags(isFirstInGroup = false, isMultiChannel = true), flags[3])
    }

    @Test
    fun `lights de deux adresses forment deux bandeaux`() {
        val flags = computeGroupFlags(listOf(light("1.1.1.1"), light("1.1.1.1"), light("2.2.2.2"), light("2.2.2.2")))
        assertEquals(listOf(true, false, true, false), flags.map { it.isFirstInGroup })
        assertTrue(flags.all { it.isMultiChannel })
    }

    @Test
    fun `fin du minuteur dans le referentiel elapsed`() {
        // Démarré il y a 100 s pour 600 s : il reste 500 s.
        assertEquals(50_000L + 500_000L, lightTimerEndsAt(startedAt = 1000.0, duration = 600.0, nowEpochSec = 1100.0, nowElapsedMs = 50_000L))
    }

    @Test
    fun `minuteur termine ou absent donne null`() {
        assertNull(lightTimerEndsAt(startedAt = 1000.0, duration = 60.0, nowEpochSec = 1100.0, nowElapsedMs = 0L))
        assertNull(lightTimerEndsAt(startedAt = null, duration = 60.0, nowEpochSec = 1100.0, nowElapsedMs = 0L))
        assertNull(lightTimerEndsAt(startedAt = 1000.0, duration = null, nowEpochSec = 1100.0, nowElapsedMs = 0L))
    }

    @Test
    fun `secondes restantes du minuteur arrondies et jamais negatives`() {
        assertEquals(90L, lightTimerRemainingSec(timerEndsAtElapsed = 100_500L, elapsedNow = 10_000L))
        assertNull(lightTimerRemainingSec(timerEndsAtElapsed = 10_000L, elapsedNow = 10_000L))
        assertNull(lightTimerRemainingSec(timerEndsAtElapsed = null, elapsedNow = 10_000L))
    }

    private val evening = kapoue.hestia.domain.model.Planning(startHour = 18, startMinute = 0, endHour = 22, endMinute = 0, days = (0..6).toSet())

    @Test
    fun `le minuteur l'emporte sur le planning`() {
        assertEquals(LightOnDetail.Timer(90), lightOnDetail(timerRemainingSec = 90, activePlanning = evening))
        assertEquals(LightOnDetail.Planned(22 * 60), lightOnDetail(timerRemainingSec = null, activePlanning = evening))
        assertEquals(LightOnDetail.None, lightOnDetail(timerRemainingSec = null, activePlanning = null))
    }

    @Test
    fun `planning actif sauf presence ou desactive aujourd'hui`() {
        val presence = evening.copy(marginMinutes = 30)
        assertEquals(evening, activeLightPlanning(listOf(presence, evening), planningDisabledToday = false) { true })
        assertNull(activeLightPlanning(listOf(evening), planningDisabledToday = true) { true })
        val once = evening.copy(date = java.time.LocalDate.of(2026, 10, 8))
        assertEquals(once, activeLightPlanning(listOf(once), planningDisabledToday = true) { true })
        assertNull(activeLightPlanning(listOf(evening), planningDisabledToday = false) { false })
    }

    @Test
    fun `seul un vrai appui bouton pendant un planning recurrent le desactive`() {
        assertTrue(disablesPlanningToday(onNow = false, buttonSource = true, wasOn = true, activeRecurringPlanning = true))
        assertFalse(disablesPlanningToday(onNow = false, buttonSource = true, wasOn = false, activeRecurringPlanning = true))
        assertFalse(disablesPlanningToday(onNow = false, buttonSource = false, wasOn = true, activeRecurringPlanning = true))
        assertFalse(disablesPlanningToday(onNow = true, buttonSource = true, wasOn = true, activeRecurringPlanning = true))
        assertFalse(disablesPlanningToday(onNow = false, buttonSource = true, wasOn = true, activeRecurringPlanning = false))
    }

    @Test
    fun `la source de l'appareil est conservee`() {
        val status = RpcResult.Success(LightStatusResult(id = 0, output = false, source = "button")).toLightStatus()
        assertEquals("button", (status as LightStatus.Online).source)
    }
}
