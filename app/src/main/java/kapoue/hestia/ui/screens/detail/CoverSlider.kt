package kapoue.hestia.ui.screens.detail

import kapoue.hestia.ui.screens.dashboard.CoverMotion
import kapoue.hestia.ui.screens.dashboard.CoverStatus
import kotlin.math.roundToInt

/** Relâchement du curseur d'un volet : position cible 0–100 (0 = fermé, 100 = ouvert). */
internal fun coverSliderTarget(value: Float): Int = value.roundToInt().coerceIn(0, 100)

/**
 * Recalage du curseur sur le relevé : jamais pendant un glissé en cours, null si la position est inconnue.
 * Pendant un mouvement, le curseur suit la position CIBLE (le moteur met 20 à 30 s à l'atteindre),
 * sinon il paraîtrait revenir en arrière juste après l'envoi de la commande.
 */
internal fun coverResyncTarget(status: CoverStatus, dragging: Boolean): Float? {
    if (dragging) return null
    val online = status as? CoverStatus.Online ?: return null
    val moving = online.motion == CoverMotion.OPENING || online.motion == CoverMotion.CLOSING
    return (if (moving && online.target != null) online.target else online.position)?.toFloat()
}
