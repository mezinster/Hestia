package kapoue.hestia.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kapoue.hestia.data.local.entity.PausedCoverEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface PausedCoverEventDao {
    @Query("SELECT * FROM paused_cover_events WHERE deviceId = :deviceId ORDER BY pausedAt ASC")
    fun observeForDevice(deviceId: Long): Flow<List<PausedCoverEvent>>

    @Insert
    suspend fun insert(paused: PausedCoverEvent): Long

    @Delete
    suspend fun delete(paused: PausedCoverEvent)
}
