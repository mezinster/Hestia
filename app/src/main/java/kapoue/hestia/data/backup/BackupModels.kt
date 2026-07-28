package kapoue.hestia.data.backup

import kotlinx.serialization.Serializable

/**
 * Format d'export/import (SPEC-V1 § 6). Couvre l'intégralité de la configuration Hestia :
 * appareils, configurations de présence, préférences. **Exclus** : journaux (activité,
 * diagnostic) et état transitoire — sans valeur de restauration.
 */
@Serializable
data class BackupFile(
    val format: String,
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val devices: List<DeviceBackup> = emptyList(),
    val presenceConfigs: List<PresenceConfigBackup> = emptyList(),
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
    /** Réglage personnalisé du minuteur « Active pour », confort propre à Hestia. */
    val presetDurationSeconds: Int? = null,
    val presetThresholdW: Int? = null,
)

/**
 * Config de présence, reliée à son appareil par (ipAddress, switchId) plutôt que par un id
 * technique — les ids sont régénérés au réimport.
 */
@Serializable
data class PresenceConfigBackup(
    val deviceIp: String,
    val deviceSwitchId: Int,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val randomMarginMinutes: Int = 20,
    val shellyScriptId: Int? = null,
    val enabled: Boolean = false,
)

/** Réservé — aucune préférence manuelle en V1 (le thème suit le système). */
@Serializable
data class PreferencesBackup(
    val placeholder: Boolean = false,
)
