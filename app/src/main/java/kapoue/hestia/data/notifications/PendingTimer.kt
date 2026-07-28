package kapoue.hestia.data.notifications

import kotlinx.serialization.Serializable

/**
 * Mémo local d'un minuteur armé depuis Hestia (« Active pour », avec ou sans coupure sur seuil de
 * consommation). Sert à détecter la fin du minuteur pour notifier, et à afficher le seuil pendant
 * que le minuteur tourne (Tableau, écran de détail) — pas une configuration d'appareil (le
 * minuteur, lui, vit et s'exécute dans le firmware). Si Hestia oublie ce mémo (données effacées),
 * au pire aucune notification et aucun rappel du seuil ne s'affichent.
 *
 * [endMillis] : instant de fin prévue (horloge du téléphone, ms). [label] : durée lisible (« 2h »).
 * [thresholdW] : seuil de coupure en Watts, ou null si aucune coupure sur seuil pour ce minuteur.
 */
@Serializable
data class PendingTimer(
    val deviceId: Long,
    val endMillis: Long,
    val label: String,
    val thresholdW: Int?,
)
