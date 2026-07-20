package kapoue.hestia.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kapoue.hestia.data.local.entity.ActivationLog
import kotlinx.coroutines.flow.Flow

/** DAO minimal en lot 1 (schéma figé) ; enrichi au lot 3. */
@Dao
interface ActivationLogDao {
    @Query("SELECT * FROM activation_logs WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(deviceId: Long, limit: Int = 20): Flow<List<ActivationLog>>

    @Insert
    suspend fun insert(log: ActivationLog)

    /** Purge des entrées de plus de :threshold (epoch ms). Appelée au lot 3. */
    @Query("DELETE FROM activation_logs WHERE timestamp < :threshold")
    suspend fun purgeOlderThan(threshold: Long)
}
