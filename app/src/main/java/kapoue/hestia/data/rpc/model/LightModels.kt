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
    /** Début du minuteur `toggle_after` (epoch Unix, s), absent sans minuteur (variateurs C1). */
    @SerialName("timer_started_at") val timerStartedAt: Double? = null,
    /** Durée totale du minuteur (s), absente sans minuteur. */
    @SerialName("timer_duration") val timerDuration: Double? = null,
)

/** Réponse de Light.GetConfig (sous-ensemble) : seul le nom du canal est exploité. */
@Serializable
data class LightConfigResult(
    val id: Int = 0,
    val name: String? = null,
)

/** Réponse de Light.Set : état précédent. */
@Serializable
data class LightSetResult(
    @SerialName("was_on") val wasOn: Boolean? = null,
)
