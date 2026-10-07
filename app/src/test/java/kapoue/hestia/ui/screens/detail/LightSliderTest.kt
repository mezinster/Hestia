package kapoue.hestia.ui.screens.detail

import kapoue.hestia.ui.screens.dashboard.LightStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightSliderTest {

    @Test
    fun `relacher le curseur allume a ce niveau`() {
        assertEquals(LightCommand(on = true, brightness = 37), sliderCommand(37.4f))
    }

    @Test
    fun `le curseur est borne entre 1 et 100`() {
        assertEquals(1, sliderCommand(0f).brightness)
        assertEquals(100, sliderCommand(140f).brightness)
    }

    @Test
    fun `pas de recalage pendant le glisse`() {
        val online = LightStatus.Online(on = true, brightness = 40, powerWatts = 3.0)
        assertNull(resyncTarget(online, dragging = true))
        assertEquals(40f, resyncTarget(online, dragging = false))
    }

    @Test
    fun `apres un echec le curseur revient a la derniere valeur lue`() {
        assertEquals(55f, revertTarget(LightStatus.Online(on = false, brightness = 55, powerWatts = null)))
        assertNull(revertTarget(LightStatus.Offline))
    }
}
