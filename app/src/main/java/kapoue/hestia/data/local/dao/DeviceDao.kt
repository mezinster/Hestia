package kapoue.hestia.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kapoue.hestia.data.local.entity.Device
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    /** Liste ordonnée par position d'affichage puis par date de création. */
    @Query("SELECT * FROM devices ORDER BY position ASC, createdAt ASC")
    fun observeAll(): Flow<List<Device>>

    /** Instantané ponctuel de la même liste, pour les cycles d'interrogation réseau. */
    @Query("SELECT * FROM devices ORDER BY position ASC, createdAt ASC")
    suspend fun getAllOnce(): List<Device>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getById(id: Long): Device?

    @Query("SELECT * FROM devices WHERE id = :id")
    fun observeById(id: Long): Flow<Device?>

    @Query("SELECT EXISTS(SELECT 1 FROM devices WHERE ipAddress = :ip AND switchId = :switchId)")
    suspend fun exists(ip: String, switchId: Int): Boolean

    @Query("SELECT COALESCE(MAX(position), -1) FROM devices")
    suspend fun maxPosition(): Int

    /** Met à jour uniquement le dernier état de sortie observé. */
    @Query("UPDATE devices SET lastKnownOutput = :output WHERE id = :id")
    suspend fun updateLastKnownOutput(id: Long, output: Boolean)

    /** Lit l'état de sortie persisté (source de vérité pour la détection de changement). */
    @Query("SELECT lastKnownOutput FROM devices WHERE id = :id")
    suspend fun getLastKnownOutput(id: Long): Boolean?

    /** Insertion échouant explicitement en cas de doublon (ipAddress, switchId). */
    @Insert
    suspend fun insert(device: Device): Long

    @Update
    suspend fun update(device: Device)

    @Delete
    suspend fun delete(device: Device)

    /** Efface tous les appareils (import = remplacement intégral ; CASCADE purge le reste). */
    @Query("DELETE FROM devices")
    suspend fun deleteAll()

    // Mode démo (captures d'écran, build debug) : appareils fictifs en plage documentaire.
    @Query("SELECT COUNT(*) FROM devices WHERE ipAddress LIKE '203.0.113.%'")
    suspend fun countDemoDevices(): Int

    @Query("DELETE FROM devices WHERE ipAddress LIKE '203.0.113.%'")
    suspend fun deleteDemoDevices()
}
