package kapoue.hestia.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import kapoue.hestia.domain.model.CoverEvent
import kapoue.hestia.domain.model.CoverEventAction
import kapoue.hestia.domain.model.normalizeCoverDays

/**
 * Événement de programmation d'un volet mis en pause : son job a été réellement supprimé de
 * l'appareil (Hestia ne stocke aucune configuration d'appareil), ce mémo local sert uniquement
 * à le recréer à l'identique à la réactivation. Même principe que [PausedPlanning].
 */
@Entity(
    tableName = "paused_cover_events",
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
data class PausedCoverEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: Long,
    val hour: Int,
    val minute: Int,
    /** Jours 0=dimanche..6=samedi, triés, séparés par des virgules. Vide = tous les jours ou unique. */
    val days: String,
    /** Date ISO (AAAA-MM-JJ) pour un événement unique, sinon null. */
    val date: String?,
    /** `open`, `close` ou `goto`. */
    val action: String,
    /** Position cible (0–100) pour `goto`, sinon null. */
    val position: Int?,
    val pausedAt: Long,
)

/** Reconstruit l'événement ; le `jobId` est nul car le job n'existe plus sur l'appareil. */
fun PausedCoverEvent.toEvent(): CoverEvent = CoverEvent(
    hour = hour,
    minute = minute,
    days = normalizeCoverDays(days.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()),
    date = date?.let { LocalDate.parse(it) },
    action = when (action) {
        "open" -> CoverEventAction.Open
        "close" -> CoverEventAction.Close
        else -> CoverEventAction.GoTo(position ?: 0)
    },
    jobId = null,
)

fun CoverEvent.toPaused(deviceId: Long, pausedAt: Long): PausedCoverEvent = PausedCoverEvent(
    deviceId = deviceId,
    hour = hour,
    minute = minute,
    days = days.sorted().joinToString(","),
    date = date?.toString(),
    action = when (action) {
        CoverEventAction.Open -> "open"
        CoverEventAction.Close -> "close"
        is CoverEventAction.GoTo -> "goto"
    },
    position = (action as? CoverEventAction.GoTo)?.position,
    pausedAt = pausedAt,
)
