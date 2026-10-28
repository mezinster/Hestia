package kapoue.hestia.ui.screens.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SecretTextTest {

    @Test
    fun `the ui state never prints the typed password`() {
        val state = AddEditUiState(passwordInput = SecretText("hunter2"))
        assertFalse(state.toString().contains("hunter2"))
        assertFalse(SecretText("hunter2").toString().contains("hunter2"))
    }

    @Test
    fun `secret text compares by value`() {
        assertEquals(SecretText("a"), SecretText("a"))
        assertEquals(SecretText("a").hashCode(), SecretText("a").hashCode())
        assertNotEquals(SecretText("a"), SecretText("b"))
    }
}
