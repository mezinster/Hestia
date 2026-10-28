package kapoue.hestia.data.repository

import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.prefs.PasswordVault
import kapoue.hestia.data.rpc.PasswordState
import kapoue.hestia.data.rpc.StoredPassword
import kapoue.hestia.domain.model.DeviceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePasswordsTest {

    private class InMemoryPasswordVault : PasswordVault {
        val map = mutableMapOf<Long, StoredPassword>()
        var writes = 0
        override fun read(deviceId: Long): StoredPassword? = map[deviceId]
        override fun write(deviceId: Long, value: StoredPassword?) {
            writes++
            if (value == null) map.remove(deviceId) else map[deviceId] = value
        }
        override fun knownIds(): Set<Long> = map.keys.toSet()
    }

    private fun device(id: Long, ip: String, switchId: Int = 0, ip2: String? = null) =
        Device(id = id, name = "d$id", ipAddress = ip, switchId = switchId, type = DeviceType.entries.first(), ip2Address = ip2)

    @Test
    fun `save covers every channel of the address`() = runTest {
        val list = listOf(device(1, ".96", 0), device(2, ".96", 1), device(3, ".97"))
        val vault = InMemoryPasswordVault()
        val passwords = DevicePasswords({ list }, vault)
        assertEquals(2, passwords.save(".96", "pw"))
        assertEquals(PasswordState.UNVERIFIED, vault.read(1)?.state)
        assertEquals(PasswordState.UNVERIFIED, vault.read(2)?.state)
        assertNull(vault.read(3))
        assertTrue(passwords.hasPassword(list[0]))
        assertFalse(passwords.hasPassword(list[2]))
        assertEquals(PasswordState.UNVERIFIED, passwords.stateOf(list[0]))
    }

    @Test
    fun `removeId erases that channel only`() = runTest {
        val list = listOf(device(1, ".96", 0), device(2, ".96", 1))
        val vault = InMemoryPasswordVault()
        val passwords = DevicePasswords({ list }, vault)
        passwords.save(".96", "pw")
        passwords.removeId(1)
        assertNull(vault.read(1))
        assertEquals(PasswordState.UNVERIFIED, vault.read(2)?.state)
    }

    @Test
    fun `load maps both addresses`() = runTest {
        val list = listOf(device(1, ".96", ip2 = "10.0.0.5"))
        val vault = InMemoryPasswordVault()
        vault.write(1, StoredPassword("pw", PasswordState.ACCEPTED))
        val loaded = DevicePasswords({ list }, vault).load()
        assertEquals(setOf(".96", "10.0.0.5"), loaded.keys)
        assertEquals(PasswordState.ACCEPTED, loaded[".96"]?.state)
    }

    @Test
    fun `load prunes passwords of removed channels`() = runTest {
        val vault = InMemoryPasswordVault()
        vault.write(9, StoredPassword("pw", PasswordState.UNVERIFIED))
        DevicePasswords({ listOf(device(1, ".96")) }, vault).load()
        assertFalse(9L in vault.knownIds())
    }

    @Test
    fun `markRejected by second address updates the channel`() = runTest {
        val list = listOf(device(1, ".96", ip2 = "10.0.0.5"))
        val vault = InMemoryPasswordVault()
        vault.write(1, StoredPassword("pw", PasswordState.UNVERIFIED))
        val passwords = DevicePasswords({ list }, vault)
        passwords.markRejected("10.0.0.5", "pw")
        assertEquals(PasswordState.REJECTED, passwords.load()[".96"]?.state)
        val writes = vault.writes
        passwords.markRejected("10.0.0.5", "pw")
        assertEquals(writes, vault.writes)
    }

    @Test
    fun `remove clears every channel of the address`() = runTest {
        val list = listOf(device(1, ".96", 0), device(2, ".96", 1))
        val vault = InMemoryPasswordVault()
        val passwords = DevicePasswords({ list }, vault)
        passwords.save(".96", "pw")
        assertEquals(2, passwords.remove(".96"))
        assertTrue(vault.knownIds().isEmpty())
    }

    @Test
    fun `mark with a non matching expected password writes nothing`() = runTest {
        val list = listOf(device(1, ".96"))
        val vault = InMemoryPasswordVault()
        vault.write(1, StoredPassword("new", PasswordState.UNVERIFIED))
        val writes = vault.writes
        val passwords = DevicePasswords({ list }, vault)
        passwords.markRejected(".96", "old")
        passwords.markAccepted(".96", "old")
        assertEquals(writes, vault.writes)
        assertEquals(PasswordState.UNVERIFIED, vault.read(1)?.state)
    }
}
