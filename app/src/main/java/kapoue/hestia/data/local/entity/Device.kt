package kapoue.hestia.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.DriverType

/**
 * Un enregistrement par **canal**, pas par appareil physique.
 * Un Plug M n'a qu'un canal (switchId = 0) ; un Pro 4PM en a quatre derrière une seule IP.
 * Chaque canal apparaît comme une tuile distincte.
 */
@Entity(
    tableName = "devices",
    indices = [Index(value = ["ipAddress", "switchId"], unique = true)],
)
data class Device(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Nom donné par l'utilisateur (ex. « Prise scooter »). */
    val name: String,
    /** Saisie manuelle, validée au format IPv4. */
    val ipAddress: String,
    /** Identifiant du canal, défaut 0. */
    val switchId: Int = 0,
    val type: DeviceType,
    /** SHELLY_GEN2 est la seule valeur en V1. */
    val driver: DriverType = DriverType.SHELLY_GEN2,
    /** Modèle rapporté par Shelly.GetDeviceInfo, informatif. */
    val model: String? = null,
    // Capacités détectées par la sonde à l'ajout (source de vérité, ≠ du type cosmétique).
    // Les defaultValue correspondent à la migration v1→v2 (obligatoire pour la validation Room).
    /** Ce canal pilote un relais (peut recevoir Switch.Set / auto_off). */
    @ColumnInfo(defaultValue = "1")
    val supportsSwitch: Boolean = true,
    /** L'appareil embarque le moteur de scripts (simulation de présence, lot 4). */
    @ColumnInfo(defaultValue = "1")
    val hasScripting: Boolean = false,
    /** L'appareil mesure la puissance. */
    @ColumnInfo(defaultValue = "0")
    val hasPowerMetering: Boolean = false,
    /** Ordre d'affichage dans la grille. */
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Vestige du journal d'activité (retiré le 2026-07-27) : servait à détecter un changement
     * d'état survenu application fermée. Plus lu ni écrit. Colonne conservée telle quelle plutôt
     * que supprimée par migration — `DROP COLUMN` n'est pas garanti sur toutes les versions de
     * SQLite embarquées par Android 11+ (minSdk 30), un risque disproportionné pour ce nettoyage.
     */
    val lastKnownOutput: Boolean? = null,
)
