package kapoue.hestia.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kapoue.hestia.domain.model.ActivationAction

/**
 * Historique local d'activité d'un appareil (SPEC-V1 § 2). Purement informatif,
 * aucune donnée sortante. Purge automatique au-delà de 30 jours (implémentée au lot 3).
 *
 * Définie dès le lot 1 pour figer le schéma Room ; exploitée à partir du lot 3.
 */
@Entity(
    tableName = "activation_logs",
    foreignKeys = [
        ForeignKey(
            entity = Device::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["deviceId"]), Index(value = ["timestamp"])],
)
data class ActivationLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val action: ActivationAction,
    /** Détail libre, ex. durée du minuteur. */
    val detail: String? = null,
)
