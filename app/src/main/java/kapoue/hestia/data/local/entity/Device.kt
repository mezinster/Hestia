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
    /**
     * Deux réglages personnalisés enregistrables pour le minuteur « Active pour » (nom + durée +
     * coupure sur seuil optionnelle). Confort propre à Hestia, pas une configuration d'appareil —
     * jamais envoyé à la prise avant que l'utilisateur ne le lance. Null = emplacement vide.
     */
    val presetDurationSeconds: Int? = null,
    /** Seuil de coupure du 1ᵉʳ réglage personnalisé, en Watts. Null = sans coupure sur seuil. */
    val presetThresholdW: Int? = null,
    /** Nom donné par l'utilisateur au 1ᵉʳ réglage (ex. « Scooter »). */
    val presetName: String? = null,
    val preset2DurationSeconds: Int? = null,
    val preset2ThresholdW: Int? = null,
    val preset2Name: String? = null,
    /**
     * Deuxième adresse IP optionnelle (ex. domicile / lieu de vacances) : Hestia bascule
     * automatiquement dessus si la première est injoignable. Null = un seul emplacement configuré.
     */
    val ip2Address: String? = null,
    /** Nom du 1ᵉʳ emplacement IP. Null = « Première adresse IP » affiché par défaut (traduit). */
    val ipName: String? = null,
    /** Nom du 2ᵉ emplacement IP. Null = « Deuxième adresse IP » affiché par défaut (traduit). */
    val ip2Name: String? = null,
    /** Dernier emplacement (1 ou 2) qui a répondu, essayé en premier au prochain appel. Null = 1. */
    val lastWorkingIpSlot: Int? = null,
    /**
     * Vrai = le 1ᵉʳ réglage Perso n'a pas de limite de durée. [presetDurationSeconds] reste
     * ignoré dans ce cas. [presetThresholdW] peut quand même être absent (retour David,
     * 2026-09-11) : la prise s'allume alors sans aucune limite automatique, ni durée ni coupure.
     */
    @ColumnInfo(defaultValue = "0")
    val presetUnlimited: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val preset2Unlimited: Boolean = false,
    /**
     * Nom de l'appareil physique (ex. « Shelly Strip 4 »), enregistré une fois à l'ajout et
     * identique sur tous les canaux d'un même appareil — sert d'en-tête stable, jamais modifié
     * par le renommage d'un canal individuel (ex. « Frigo »). Pour un appareil mono-canal,
     * toujours identique à [name]. Vide = pas encore migré (voir `fixLegacyChannelNames`).
     */
    @ColumnInfo(defaultValue = "")
    val deviceName: String = "",
    /**
     * MAC de l'appareil physique (identique au « Cloud ID » Shelly), mis en cache dès qu'il est lu
     * en local sur n'importe quel canal — c'est le seul moyen de le connaître au moment précis où
     * l'appareil est injoignable en local et où on en aurait besoin pour le repli cloud (lot 3).
     * Identique sur tous les canaux d'un même appareil physique, comme [deviceName]. Null tant que
     * jamais lu.
     */
    val cloudId: String? = null,
    /**
     * Résultat de la dernière vérification automatique du firmware (une fois par jour maximum,
     * au lancement, uniquement si le Cloud Shelly est activé pour cet appareil — voir CLAUDE.md et
     * `DeviceRepository.checkFirmwareUpdatesIfDue`). Faux tant que jamais vérifié, ou à jour, ou
     * après une installation réussie. N'affecte jamais le comportement de l'appareil, purement
     * informatif (bandeau sur la tuile).
     */
    @ColumnInfo(defaultValue = "0")
    val firmwareUpdateAvailable: Boolean = false,
)
