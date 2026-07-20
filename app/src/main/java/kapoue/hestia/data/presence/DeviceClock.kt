package kapoue.hestia.data.presence

/** Horloge rapportée par l'appareil (pour le contrôle de dérive). */
data class DeviceClock(
    /** Epoch Unix en secondes (UTC). */
    val unixtime: Long,
    /** Heure locale telle que l'appareil la rapporte, ex. « 05:09 ». */
    val timeLabel: String?,
)
