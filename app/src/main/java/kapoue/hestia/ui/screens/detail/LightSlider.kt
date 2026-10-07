package kapoue.hestia.ui.screens.detail

import kapoue.hestia.ui.screens.dashboard.LightStatus
import kotlin.math.roundToInt

/** Commande Light.Set issue de l'écran Détail. */
data class LightCommand(val on: Boolean?, val brightness: Int?)

/** Relâchement du curseur : allume toujours, à ce niveau (1–100 ; l'arrêt est le rôle du bouton). */
internal fun sliderCommand(value: Float): LightCommand =
    LightCommand(on = true, brightness = value.roundToInt().coerceIn(1, 100))

/** Recalage du curseur sur le relevé : jamais pendant un glissé en cours (le doigt prime), sinon [revertTarget]. */
internal fun resyncTarget(status: LightStatus, dragging: Boolean): Float? =
    if (dragging) null else revertTarget(status)

/** Valeur où replacer le curseur après un échec : la dernière lue sur l'appareil, null si inconnue. */
internal fun revertTarget(status: LightStatus): Float? = (status as? LightStatus.Online)?.brightness?.toFloat()
