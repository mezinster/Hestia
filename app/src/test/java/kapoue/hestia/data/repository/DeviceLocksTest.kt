package kapoue.hestia.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sérialisation par appareil des opérations de programmation des volets (lot S2). */
class DeviceLocksTest {

    /** Simule une resynchronisation : lit la liste, attend (RPC), puis met à jour ce qui n'est pas fait. */
    private suspend fun fakeResync(done: MutableSet<Int>, updates: MutableList<Int>) {
        val pending = listOf(1, 2).filter { it !in done }
        delay(100)
        for (job in pending) { updates += job; done += job }
    }

    @Test
    fun `deux resynchronisations concurrentes du meme appareil font une mise a jour par job`() = runTest {
        val locks = DeviceLocks()
        val done = mutableSetOf<Int>()
        val updates = mutableListOf<Int>()
        (1..2).map { async { locks.withLock(7L) { fakeResync(done, updates) } } }.awaitAll()
        assertEquals(listOf(1, 2), updates)
    }

    @Test
    fun `deux appareils differents ne s'attendent pas`() = runTest {
        val locks = DeviceLocks()
        val order = mutableListOf<String>()
        listOf(
            async { locks.withLock(1L) { order += "a1"; delay(100); order += "a2" } },
            async { locks.withLock(2L) { order += "b1"; delay(100); order += "b2" } },
        ).awaitAll()
        assertEquals(listOf("a1", "b1", "a2", "b2"), order)
    }
}
