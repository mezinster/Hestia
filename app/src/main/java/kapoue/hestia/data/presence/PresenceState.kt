package kapoue.hestia.data.presence

/** État réel de la simulation de présence sur l'appareil, lu via Script.List. */
data class PresenceState(
    /** Un script `hestia_presence` existe sur l'appareil. */
    val deployed: Boolean,
    /** …et il est en cours d'exécution. */
    val running: Boolean,
    val scriptId: Int?,
)
