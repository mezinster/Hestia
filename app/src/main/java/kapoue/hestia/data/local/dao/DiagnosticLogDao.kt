package kapoue.hestia.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kapoue.hestia.data.local.entity.DiagnosticLog

@Dao
interface DiagnosticLogDao {

    @Insert
    suspend fun insert(log: DiagnosticLog)

    @Query("SELECT COUNT(*) FROM diagnostic_logs")
    suspend fun count(): Int

    /** Supprime les [n] entrées les plus anciennes (maintien du tampon circulaire). */
    @Query("DELETE FROM diagnostic_logs WHERE id IN (SELECT id FROM diagnostic_logs ORDER BY id ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    /** Toutes les entrées, de la plus récente à la plus ancienne (pour le partage). */
    @Query("SELECT * FROM diagnostic_logs ORDER BY id DESC")
    suspend fun getAll(): List<DiagnosticLog>
}
