package kapoue.hestia.data.notifications

import kotlinx.serialization.Serializable

/**
 * Mémo local d'un minuteur armé depuis Hestia (« Active pour », avec ou sans coupure sur seuil de
 * consommation). Sert **uniquement** à détecter la fin du minuteur pour notifier — ce n'est pas une
 * configuration d'appareil (le minuteur, lui, vit et s'exécute dans le firmware). Si Hestia oublie
 * ce mémo (données effacées), au pire aucune notification n'est émise.
 *
 * [endMillis] : instant de fin prévue (horloge du téléphone, ms). [label] : durée lisible (« 2h »).
 * [cutoff] : vrai si une coupure sur seuil de consommation est active pour ce minuteur.
 */
@Serializable
data class PendingTimer(
    val deviceId: Long,
    val endMillis: Long,
    val label: String,
    val cutoff: Boolean,
)
