package kapoue.hestia.ui.screens.detail

import kapoue.hestia.ui.screens.dashboard.CoverStatus
import kotlin.math.roundToInt

/** Relâchement du curseur d'un volet : position cible 0–100 (0 = fermé, 100 = ouvert). */
internal fun coverSliderTarget(value: Float): Int = value.roundToInt().coerceIn(0, 100)

/** Recalage du curseur sur le relevé : jamais pendant un glissé en cours, null si la position est inconnue. */
internal fun coverResyncTarget(status: CoverStatus, dragging: Boolean): Float? =
    if (dragging) null else (status as? CoverStatus.Online)?.position?.toFloat()
