package kapoue.hestia.data.rpc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Réponse de Light.GetStatus (sous-ensemble) — doc officielle Gen2+, composant Light. */
@Serializable
data class LightStatusResult(
    val id: Int,
    val output: Boolean = false,
    /** Luminosité en pourcentage (0–100). */
    val brightness: Double? = null,
    /** Puissance active instantanée (W), seulement si l'appareil la mesure. */
    val apower: Double? = null,
    val source: String? = null,
)

/** Réponse de Light.Set : état précédent. */
@Serializable
data class LightSetResult(
    @SerialName("was_on") val wasOn: Boolean? = null,
)
