package kapoue.hestia.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
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

    /** Insertion échouant explicitement en cas de doublon (ipAddress, switchId). */
    @Insert
    suspend fun insert(device: Device): Long

    @Update
    suspend fun update(device: Device)

    /** Mémorise l'emplacement IP (1 ou 2) qui a répondu, sans repasser par un [update] complet. */
    @Query("UPDATE devices SET lastWorkingIpSlot = :slot WHERE id = :deviceId")
    suspend fun updateLastWorkingIpSlot(deviceId: Long, slot: Int)

    /**
     * Écrit l'épingle HTTPS de tous les canaux de [ip] sans réécrire leurs lignes (une copie
     * périmée ne doit jamais écraser une autre colonne, ni être écrasée). Renvoie le nombre de canaux.
     */
    @Query("UPDATE devices SET certPin = :pin WHERE ipAddress = :ip")
    suspend fun updateCertPin(ip: String, pin: String?): Int

    /**
     * Relit chaque ligne [ids] et y applique [transform], le tout dans une même transaction :
     * aucune écriture concurrente (l'épingle HTTPS notamment) ne peut se glisser entre la lecture et
     * l'écriture. Voir [updateFreshRows].
     */
    @Transaction
    suspend fun updateFresh(ids: List<Long>, transform: (Device) -> Device): Int =
        updateFreshRows(ids, read = { getById(it) }, write = { update(it) }, transform = transform)

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
