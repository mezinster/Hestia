package kapoue.hestia.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cache local de confort des paramètres de simulation de présence (SPEC-V1 § 2).
 * La source de vérité reste le script déployé sur l'appareil : au chargement, vérifier via
 * Script.List que le script est réellement présent et actif.
 *
 * Définie dès le lot 1 pour figer le schéma Room ; exploitée au lot 4.
 */
@Entity(
    tableName = "presence_configs",
    foreignKeys = [
        ForeignKey(
            entity = Device::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["deviceId"], unique = true)],
)
data class PresenceConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: Long,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val randomMarginMinutes: Int = 20,
    /** ID retourné par Script.Create. */
    val shellyScriptId: Int? = null,
    /** Dernier état connu. */
    val enabled: Boolean = false,
)
