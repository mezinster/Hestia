package kapoue.hestia.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kapoue.hestia.data.local.entity.PresenceConfig

/** DAO minimal en lot 1 (schéma figé) ; enrichi au lot 4. */
@Dao
interface PresenceConfigDao {
    @Query("SELECT * FROM presence_configs WHERE deviceId = :deviceId")
    suspend fun getForDevice(deviceId: Long): PresenceConfig?

    @Upsert
    suspend fun upsert(config: PresenceConfig)
}
