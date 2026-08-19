package kapoue.hestia.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Planning mis en pause : ses programmes `Schedule` (et son script de coupure éventuel) ont été
 * réellement supprimés de l'appareil (Hestia ne stocke aucune configuration d'appareil), ce
 * mémo local sert uniquement à recréer le planning à l'identique au moment de la réactivation.
 * Seule exception délibérée à la règle « Hestia ne stocke rien », comparable aux réglages Perso.
 */
@Entity(
    tableName = "paused_plannings",
    foreignKeys = [
        ForeignKey(
            entity = Device::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["deviceId"])],
)
data class PausedPlanning(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: Long,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    /** Jours 0=dimanche..6=samedi, séparés par des virgules. Vide pour un planning Unique. */
    val days: String,
    /** Date ISO (AAAA-MM-JJ) pour un planning Unique, sinon null. */
    val date: String? = null,
    val cutoffThresholdW: Int? = null,
    /** Non nul = c'était une simulation de présence, marge en minutes (voir [Planning]). */
    val marginMinutes: Int? = null,
    val pausedAt: Long = System.currentTimeMillis(),
)
