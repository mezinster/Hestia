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
     * Tient la place de la durée de charge dans le texte traduit de la notif de coupure
     * (`getString(R.string.notif_cutoff_triggered_with_duration, DURATION_MARKER)`) : la durée
     * n'est connue qu'à l'exécution du script sur l'appareil, et sa place dans la phrase dépend
     * de la langue (en tête en anglais/français/russe depuis le 2026-10-29).
     */
    const val DURATION_MARKER = "@@DUREE@@"

    /**
     * Expression JS du corps de la notif : [template] découpé autour de [DURATION_MARKER], chaque
     * morceau en littéral JS échappé, [durationExpr] (ex. `fmtDur(durationSec)`) à sa place. Sans
     * marqueur (ancien texte), la durée est ajoutée à la fin après une espace, comme avant.
     */
    fun bodyWithDuration(template: String, durationExpr: String): String {
        val at = template.indexOf(DURATION_MARKER)
        if (at < 0) return "\"${jsString(template)} \" + $durationExpr"
        val before = template.substring(0, at)
        val after = template.substring(at + DURATION_MARKER.length)
        return listOfNotNull(
            before.takeIf { it.isNotEmpty() }?.let { "\"${jsString(it)}\"" },
            durationExpr,
            after.takeIf { it.isNotEmpty() }?.let { "\"${jsString(it)}\"" },
        ).joinToString(" + ")
    }

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

    /**
     * Comme [callDynamicTitle], mais le **corps** est aussi une expression JS — pour le cas où le
     * texte varie aussi selon le canal (ex. la notif de fin de minuteur, dont le texte dépend du
     * réglage lancé sur ce canal précis, pas seulement de son nom).
     */
    fun callDynamicTitleAndBody(topic: String?, titleExpr: String, bodyExpr: String): String {
        if (topic == null) return ""
        return "Shelly.call(\"HTTP.Request\", { method: \"POST\", url: \"https://ntfy.sh/${jsString(topic)}\", " +
            "body: $bodyExpr, timeout: 5, headers: { Title: $titleExpr } });"
    }
}
