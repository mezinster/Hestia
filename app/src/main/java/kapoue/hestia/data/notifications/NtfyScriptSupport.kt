package kapoue.hestia.data.notifications

/**
 * Support commun aux scripts Shelly générés qui doivent notifier via ntfy (coupure sur seuil,
 * présence, minuteur). Le titre (nom de la prise) et le corps (nom du Perso, texte) viennent de
 * saisies libres de l'utilisateur : contrairement au reste des scripts générés (jusqu'ici toujours
 * des nombres), ce texte doit être échappé avant d'être injecté dans le JS du script.
 */
object NtfyScriptSupport {

    /** Échappe une chaîne pour l'insérer telle quelle dans un littéral JS entre guillemets doubles. */
    fun jsString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", "")

    /**
     * Instruction JS d'appel ntfy (`HTTP.Request`, seule méthode Shelly à accepter des en-têtes —
     * nécessaire pour le titre), ou chaîne vide si [topic] est nul (ntfy désactivé : rien à
     * générer). Toujours à placer **après** l'action réelle sur le canal dans le script appelant,
     * jamais avant — un ntfy.sh lent ou injoignable ne doit jamais retarder l'action.
     */
    fun call(topic: String?, title: String, body: String): String {
        if (topic == null) return ""
        return "Shelly.call(\"HTTP.Request\", { method: \"POST\", url: \"https://ntfy.sh/${jsString(topic)}\", " +
            "body: \"${jsString(body)}\", timeout: 5, headers: { Title: \"${jsString(title)}\" } });"
    }

    /**
     * Comme [call], mais [titleExpr] est une **expression JS** (ex. une variable) plutôt qu'un
     * littéral — pour les scripts qui surveillent plusieurs canaux à la fois (2026-08-17, script
     * superviseur) : le titre (nom de la prise) dépend du canal qui notifie, connu seulement à
     * l'exécution du script, pas à sa génération.
     */
    fun callDynamicTitle(topic: String?, titleExpr: String, body: String): String {
        if (topic == null) return ""
        return "Shelly.call(\"HTTP.Request\", { method: \"POST\", url: \"https://ntfy.sh/${jsString(topic)}\", " +
            "body: \"${jsString(body)}\", timeout: 5, headers: { Title: $titleExpr } });"
    }
}
