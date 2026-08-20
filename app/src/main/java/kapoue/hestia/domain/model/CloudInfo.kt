package kapoue.hestia.domain.model

/**
 * État Cloud Shelly d'un appareil physique (`Cloud.GetConfig` + `Cloud.GetStatus`), toujours relu
 * en direct — jamais stocké par Hestia. Un seul réglage par appareil physique (pas par canal).
 *
 * Fonctionnalité strictement opt-in (voir CLAUDE.md) : Hestia ne fait qu'activer/désactiver le
 * canal cloud du firmware et afficher son état ; le rattachement à un compte Shelly (création de
 * compte, appairage de l'appareil) se fait entièrement en dehors de Hestia, via l'appli Shelly ou
 * `control.shelly.cloud` — jamais aucun champ d'identifiant/mot de passe/jeton ici.
 */
sealed interface CloudInfo {
    /**
     * [macId] provient de `Shelly.GetDeviceInfo` : c'est exactement la valeur affichée
     * « Cloud ID » dans l'interface native de l'appareil (validé en direct le 2026-08-20).
     * [server] est le serveur régional réellement assigné une fois connecté (ex.
     * `shelly-api-eu.shelly.cloud:6022/jrpc`), absent tant que le cloud n'a jamais été activé.
     */
    data class Available(
        val enabled: Boolean,
        val connected: Boolean,
        val server: String?,
        val macId: String?,
    ) : CloudInfo

    /** Appareil injoignable, ou trop ancien pour exposer le composant Cloud. */
    data object Unavailable : CloudInfo
}
