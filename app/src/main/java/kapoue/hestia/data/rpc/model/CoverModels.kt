package kapoue.hestia.data.rpc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Réponse de Cover.GetStatus (sous-ensemble) — doc officielle Gen2+, composant Cover. */
@Serializable
data class CoverStatusResult(
    val id: Int,
    /** open, closed, opening, closing, stopped ou calibrating. */
    val state: String? = null,
    /** Position en pourcentage (0 fermé – 100 ouvert), absente ou nulle si non calibré. */
    @SerialName("current_pos") val currentPos: Int? = null,
    @SerialName("target_pos") val targetPos: Int? = null,
    /** Vrai si l'appareil sait aller à une position (volet calibré). */
    @SerialName("pos_control") val posControl: Boolean = false,
    /** Puissance active instantanée (W). */
    val apower: Double? = null,
    val source: String? = null,
    val errors: List<String> = emptyList(),
)

/** Réponse de Cover.GetConfig (sous-ensemble) : seul le nom du canal est exploité. */
@Serializable
data class CoverConfigResult(
    val id: Int = 0,
    val name: String? = null,
)
