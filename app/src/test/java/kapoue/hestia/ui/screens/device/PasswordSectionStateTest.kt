package kapoue.hestia.ui.screens.device

import kapoue.hestia.data.rpc.AccessIssue
import kapoue.hestia.data.rpc.PasswordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordSectionStateTest {

    @Test
    fun `nothing stored and no issue hides the section`() {
        assertNull(passwordSectionStateOf(null, null))
    }

    @Test
    fun `wrong password issue is rejected`() {
        assertEquals(PasswordSectionState.REJECTED, passwordSectionStateOf(null, AccessIssue.PASSWORD_WRONG))
        assertEquals(PasswordSectionState.REJECTED, passwordSectionStateOf(PasswordState.ACCEPTED, AccessIssue.PASSWORD_WRONG))
    }

    @Test
    fun `stored rejected is rejected`() {
        assertEquals(PasswordSectionState.REJECTED, passwordSectionStateOf(PasswordState.REJECTED, null))
    }

    @Test
    fun `required issue is required`() {
        assertEquals(PasswordSectionState.REQUIRED, passwordSectionStateOf(null, AccessIssue.PASSWORD_REQUIRED))
        assertEquals(PasswordSectionState.REQUIRED, passwordSectionStateOf(PasswordState.UNVERIFIED, AccessIssue.PASSWORD_REQUIRED))
    }

    @Test
    fun `stored unverified and accepted`() {
        assertEquals(PasswordSectionState.UNVERIFIED, passwordSectionStateOf(PasswordState.UNVERIFIED, null))
        assertEquals(PasswordSectionState.ACCEPTED, passwordSectionStateOf(PasswordState.ACCEPTED, null))
    }

    @Test
    fun `non password issue is ignored`() {
        assertNull(passwordSectionStateOf(null, AccessIssue.CERT_CHANGED))
        assertEquals(PasswordSectionState.ACCEPTED, passwordSectionStateOf(PasswordState.ACCEPTED, AccessIssue.THROTTLED))
    }
}
