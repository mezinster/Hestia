package kapoue.hestia.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kapoue.hestia.data.local.entity.PausedPlanning
import kotlinx.coroutines.flow.Flow

@Dao
interface PausedPlanningDao {
    @Query("SELECT * FROM paused_plannings WHERE deviceId = :deviceId ORDER BY pausedAt ASC")
    fun observeForDevice(deviceId: Long): Flow<List<PausedPlanning>>

    @Insert
    suspend fun insert(paused: PausedPlanning): Long

    @Delete
    suspend fun delete(paused: PausedPlanning)
}
