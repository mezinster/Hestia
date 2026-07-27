package kapoue.hestia.domain.model

/**
 * Résultat d'une vérification manuelle de mise à jour firmware (`Shelly.CheckForUpdate`).
 *
 * Seule opération du projet qui fait sortir l'appareil du réseau local (il interroge les
 * serveurs Shelly) — strictement à la demande explicite de l'utilisateur, jamais automatique.
 *
 * Une version **bêta** disponible est signalée à titre informatif seulement : Hestia ne propose
 * jamais de l'installer (trop risqué pour un outil grand public).
 */
sealed interface FirmwareCheckResult {
    data class UpToDate(val installedVersion: String) : FirmwareCheckResult
    data class UpdateAvailable(val installedVersion: String, val newVersion: String) : FirmwareCheckResult
    data class BetaOnly(val installedVersion: String, val betaVersion: String) : FirmwareCheckResult
    data object Error : FirmwareCheckResult
}
