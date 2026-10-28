package kapoue.hestia.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpiredOnceGuardTest {

    private val p34: Pair<Int?, Int?> = 3 to 4
    private val p5: Pair<Int?, Int?> = 5 to null

    @Test
    fun `only the first reader claims an expired Unique`() {
        val guard = ExpiredOnceGuard()
        val first = guard.claim("192.168.1.96", listOf(p34), ::key)
        val second = guard.claim("192.168.1.96", listOf(p34), ::key)
        assertEquals(listOf(p34), first)
        assertEquals(emptyList<Pair<Int?, Int?>>(), second)
    }

    @Test
    fun `the same job ids on another device are claimed separately`() {
        val guard = ExpiredOnceGuard()
        guard.claim("192.168.1.96", listOf(p34), ::key)
        assertEquals(listOf(p34), guard.claim("192.168.1.98", listOf(p34), ::key))
    }

    @Test
    fun `a batch keeps only the not yet claimed plannings`() {
        val guard = ExpiredOnceGuard()
        guard.claim("192.168.1.96", listOf(p34), ::key)
        assertEquals(listOf(p5), guard.claim("192.168.1.96", listOf(p34, p5), ::key))
    }

    private fun key(p: Pair<Int?, Int?>) = "${p.first}#${p.second}"
}
