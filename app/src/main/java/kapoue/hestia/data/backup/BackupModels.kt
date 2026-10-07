package kapoue.hestia.data.backup

import kotlinx.serialization.Serializable

/**
 * Format d'export/import (SPEC-V1 § 6). Couvre la liste des appareils et leurs réglages de
 * confort propres à Hestia (préréglages, adresses IP). **Exclus** : tout ce qui vit sur
 * l'appareil lui-même (plannings, présence, minuteur bouton, seuils — jamais stocké par Hestia,
 * voir CLAUDE.md), ainsi que les journaux (activité, diagnostic) et l'état transitoire — sans
 * valeur de restauration hors ligne.
 *
 * A porté un champ `presenceConfigs` jusqu'au 2026-08-24 : vestige d'avant la fusion Planning/
 * Présence du 2026-08-18 (la présence vivait alors dans Room, comme un cas particulier), retiré
 * une fois confirmé qu'il n'exportait plus jamais rien (voir BACKLOG.md). Un ancien fichier
 * exporté avant cette date qui porterait encore ce champ reste lisible (`ignoreUnknownKeys`),
 * simplement ignoré.
 */
@Serializable
data class BackupFile(
    val format: String,
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val devices: List<DeviceBackup> = emptyList(),
    val preferences: PreferencesBackup = PreferencesBackup(),
)

@Serializable
data class DeviceBackup(
    val name: String,
    val ipAddress: String,
    val switchId: Int,
    val type: String,
    val driver: String,
    val model: String? = null,
    val supportsSwitch: Boolean = true,
    val hasScripting: Boolean = false,
    val hasPowerMetering: Boolean = false,
    val position: Int = 0,
    /** Réglages personnalisés du minuteur « Active pour », confort propre à Hestia. */
    val presetDurationSeconds: Int? = null,
    val presetThresholdW: Int? = null,
    val presetName: String? = null,
    val presetUnlimited: Boolean = false,
    val preset2DurationSeconds: Int? = null,
    val preset2ThresholdW: Int? = null,
    val preset2Name: String? = null,
    val preset2Unlimited: Boolean = false,
    /** Deuxième adresse IP optionnelle (ex. domicile / vacances). Absente = un seul emplacement. */
    val ip2Address: String? = null,
    val ipName: String? = null,
    val ip2Name: String? = null,
    /** Canal variateur (2026-10-07). Absent d'une ancienne sauvegarde = relais. */
    val isLight: Boolean = false,
)

/** Réservé — aucune préférence manuelle en V1 (le thème suit le système). */
@Serializable
data class PreferencesBackup(
    val placeholder: Boolean = false,
)
