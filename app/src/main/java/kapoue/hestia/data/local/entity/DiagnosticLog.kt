package kapoue.hestia.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Journal de diagnostic (SPEC-V1 § 7), exploité comme un **tampon circulaire** borné
 * (les plus anciennes entrées sont purgées au-delà de 2 000). Actif en production, local,
 * jamais envoyé automatiquement. Aucun mot de passe ne doit y figurer.
 */
@Entity(
    tableName = "diagnostic_logs",
    indices = [Index(value = ["timestamp"])],
)
data class DiagnosticLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** INFO / WARN / ERROR. */
    val level: String,
    /** Catégorie courte : ui, rpc, db… */
    val tag: String,
    val message: String,
)
