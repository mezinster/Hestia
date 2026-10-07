package kapoue.hestia.data.rpc

import java.time.LocalDate
import kapoue.hestia.data.rpc.model.ScheduleCall
import kapoue.hestia.data.rpc.model.ScheduleJob
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverScheduleRpcTest {

    @Test
    fun `appels des trois actions`() {
        assertEquals("""{"method":"Cover.Open","params":{"id":0}}""", coverActionCall(0, CoverEventAction.Open).toString())
        assertEquals("""{"method":"Cover.Close","params":{"id":1}}""", coverActionCall(1, CoverEventAction.Close).toString())
        assertEquals(
            """{"method":"Cover.GoToPosition","params":{"id":0,"pos":50}}""",
            coverActionCall(0, CoverEventAction.GoTo(50)).toString(),
        )
    }

    @Test
    fun `appel ntfy de meme forme que l'amont`() {
        assertEquals(
            """{"method":"HTTP.Request","params":{"method":"POST","url":"https://ntfy.sh/mon-sujet","body":"Volet ouvert","timeout":5,"headers":{"Title":"Salon"}}}""",
            coverNtfyCall(NtfyTexts("mon-sujet", "Salon", "Volet ouvert")).toString(),
        )
    }

    @Test
    fun `creation sans ntfy ne contient aucun HTTP Request`() {
        val p = coverScheduleCreateParams("0 30 7 * * 1,2", 0, CoverEventAction.Open, null)
        assertEquals("true", p["enable"]!!.jsonPrimitive.content)
        assertEquals("0 30 7 * * 1,2", p["timespec"]!!.jsonPrimitive.content)
        val calls = p["calls"]!!.jsonArray
        assertEquals(1, calls.size)
        assertEquals("Cover.Open", calls[0].jsonObject["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `creation avec ntfy place HTTP Request en dernier`() {
        val p = coverScheduleCreateParams("0 30 7 * * *", 0, CoverEventAction.Close, NtfyTexts("t", "T", "B"))
        val calls = p["calls"]!!.jsonArray
        assertEquals(2, calls.size)
        assertEquals("Cover.Close", calls[0].jsonObject["method"]!!.jsonPrimitive.content)
        assertEquals("HTTP.Request", calls[1].jsonObject["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `timespec recurrent et unique`() {
        assertEquals("0 30 7 * * 1,2", coverTimespec(CoverEvent(7, 30, setOf(1, 2), null, CoverEventAction.Open)))
        assertEquals(
            "0 5 18 24 12 *",
            coverTimespec(CoverEvent(18, 5, emptySet(), LocalDate.of(2030, 12, 24), CoverEventAction.Close)),
        )
    }

    private fun call(method: String, vararg params: Pair<String, Int>): ScheduleCall =
        ScheduleCall(method, buildJsonObject { params.forEach { (k, v) -> put(k, v) } })

    @Test
    fun `relecture d'une liste mixte pour le volet 0`() {
        val http = ScheduleCall("HTTP.Request", buildJsonObject { put("url", "https://ntfy.sh/x") })
        val jobs = listOf(
            ScheduleJob(1, true, "0 0 8 * * *", listOf(call("Switch.Set", "id" to 0))),
            ScheduleJob(2, true, "0 0 8 * * *", listOf(call("Light.Set", "id" to 0))),
            ScheduleJob(3, true, "0 30 7 * * 1,2,3,4,5", listOf(call("Cover.Open", "id" to 0))),
            ScheduleJob(4, true, "0 0 20 * * *", listOf(call("Cover.Close", "id" to 1))),
            ScheduleJob(5, true, "0 0 12 * * *", listOf(call("Cover.GoToPosition", "id" to 0, "pos" to 50), http)),
            ScheduleJob(6, true, "0 0 9 * * *", listOf(call("Script.Start", "id" to 0))),
            ScheduleJob(7, true, "n'importe quoi", listOf(call("Cover.Open", "id" to 0))),
            ScheduleJob(8, true, "0 0 9 * * *", listOf(call("Cover.GoToPosition", "id" to 0))),
        )
        assertEquals(
            listOf(
                CoverEvent(7, 30, setOf(1, 2, 3, 4, 5), null, CoverEventAction.Open, 3),
                CoverEvent(12, 0, emptySet(), null, CoverEventAction.GoTo(50), 5),
            ),
            coverEventsFrom(jobs, 0),
        )
    }

    @Test
    fun `tous les jours est relu avec des jours vides`() {
        val jobs = listOf(ScheduleJob(9, true, "0 0 21 * * *", listOf(call("Cover.Close", "id" to 0))))
        assertEquals(
            listOf(CoverEvent(21, 0, action = CoverEventAction.Close, jobId = 9)),
            coverEventsFrom(jobs, 0),
        )
    }

    @Test
    fun `aller-retour tous les jours`() {
        val original = CoverEvent(21, 0, action = CoverEventAction.Close)
        val job = ScheduleJob(4, true, coverTimespec(original), listOf(call("Cover.Close", "id" to 0)))
        val back = coverEventsFrom(listOf(job), 0).single()
        assertEquals(true, back.sameSlotAs(original))
    }
}
