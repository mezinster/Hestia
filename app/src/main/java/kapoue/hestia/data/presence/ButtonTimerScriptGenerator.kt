package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport
import kotlinx.serialization.json.Json

/**
 * Script **persistant** : veille en permanence un appui sur le bouton physique et arme un
 * minuteur natif par-dessus (avec coupure sur seuil optionnelle), sans toucher au comportement
 * natif du bouton — repose sur le champ `source` de `Switch.GetStatus`, pas sur un composant
 * Input (absent sur les modèles mono-canal validés en direct : pas d'appui long/double possible
 * sur ce matériel, le bouton est câblé en dur au relais).
 *
 * **La valeur exacte de `source` varie selon le modèle** : `"button"` sur Plug M Gen3 (validé en
 * direct), `"short_push"` sur Strip 4 (validé en direct après un bug où le script ne s'armait
 * jamais — la comparaison ne matchait pas). [isButtonSource] accepte les deux, plus les variantes
 * de pression plausibles (`long_push`, `double_push`, `triple_push`) par prudence, même non
 * confirmées sur du matériel réel.
 *
 * Contrairement aux autres scripts de coupure (déployés une fois, se désactivent après usage),
 * celui-ci ne se supprime jamais lui-même : après chaque cycle (fin naturelle, coupure sur
 * seuil, ou nouvel appui pendant qu'il tourne), il se remet en veille pour l'appui suivant.
 * `enable:true` (contrairement au script de coupure d'un planning) : doit redémarrer seul après
 * un redémarrage de l'appareil, la fonctionnalité ne doit pas se désactiver silencieusement.
 */
object ButtonTimerScriptGenerator {

    /**
     * Nom **par canal** — un appareil multi-canaux (ex. Strip 4) partage un seul moteur de
     * scripts entre tous ses relais : un nom fixe ferait retrouver/écraser le script d'un autre
     * canal du même appareil au lieu du sien (bug vécu en direct, corrigé).
     */
    fun scriptName(switchId: Int): String = "hestia_button_timer_$switchId"

    private const val MARKER = "// hestia_button_timer:"
    private const val BELOW_SEC = 60
    /** Grâce avant surveillance quand [generate.durationSec] est null (sans limite de durée). */
    private const val UNLIMITED_GRACE_SEC = 15 * 60

    /**
     * [durationSec] null = **sans limite de durée** : aucun minuteur natif armé à l'appui, la
     * prise reste allumée jusqu'à la coupure sur seuil (obligatoire dans ce cas — sans durée ni
     * seuil, rien ne l'éteindrait jamais). Une période de grâce fixe de 15 min précède alors toute
     * surveillance, absente sinon (comportement historique inchangé avec une durée).
     *
     * [ntfyTopic] non nul = notifie via ntfy à la fin ([ntfyEndBody]) et à une coupure sur seuil
     * ([ntfyCutoffBody]). Un appui bouton qui annule le minuteur en cours (source de nouveau un
     * appui bouton au moment de l'extinction, voir `isButtonSource`) ne notifie jamais — c'est
     * une action manuelle délibérée.
     */
    fun generate(
        switchId: Int,
        durationSec: Int?,
        thresholdW: Int?,
        ntfyTopic: String? = null,
        ntfyTitle: String = "",
        ntfyEndBody: String = "",
        ntfyCutoffBody: String = "",
    ): String {
        val marker = "[${durationSec ?: "null"},${thresholdW ?: "null"}]"
        val durationLiteral = durationSec?.toString() ?: "null"
        val thresholdLiteral = thresholdW?.toString() ?: "null"
        val graceSec = if (durationSec == null) UNLIMITED_GRACE_SEC else 0
        val ntfyEnd = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyEndBody)
        val ntfyCutoff = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyCutoffBody)
        return """
        // Généré par Hestia — minuteur déclenché par le bouton physique
        $MARKER$marker
        let CFG = { switchId: $switchId, durationSec: $durationLiteral, thresholdW: $thresholdLiteral, belowSec: $BELOW_SEC, graceSec: $graceSec };
        let armed = false;
        let wasOn = false;
        let belowSince = null;
        let onSince = null;

        // La valeur exacte de "source" pour un appui bouton varie selon le modèle de prise
        // (ex. "button" sur Plug M, "short_push" sur Strip 4) — accepte les variantes plausibles.
        function isButtonSource(src) {
          return src === "button" || src === "short_push" || src === "long_push" || src === "double_push" || src === "triple_push";
        }

        Timer.set(1000, true, function () {
          let st = Shelly.getComponentStatus("switch", CFG.switchId);
          if (!st) return;

          if (!armed) {
            // En veille : un appui qui vient d'allumer arme le minuteur par-dessus (ou, sans
            // durée, laisse simplement l'allumage natif du bouton tel quel).
            if (st.output && !wasOn && isButtonSource(st.source)) {
              armed = true;
              belowSince = null;
              onSince = null;
              if (CFG.durationSec !== null) {
                Shelly.call("Switch.Set", { id: CFG.switchId, on: true, toggle_after: CFG.durationSec });
              }
            }
            wasOn = st.output;
            return;
          }

          if (wasOn && !st.output) {
            // Éteinte pendant que le minuteur tournait : fin naturelle, coupure, ou action
            // manuelle. Un nouvel appui bouton pour annuler ne notifie jamais (délibéré).
            if (!isButtonSource(st.source)) {
              $ntfyEnd
            }
            armed = false;
            belowSince = null;
            wasOn = false;
            return;
          }
          wasOn = st.output;

          let sys = Shelly.getComponentStatus("sys");
          let now = (sys && sys.unixtime) ? sys.unixtime : null;
          if (onSince === null && now !== null) onSince = now;
          if (CFG.graceSec > 0 && onSince !== null && now !== null && (now - onSince) < CFG.graceSec) return;

          if (CFG.thresholdW !== null && st.output) {
            let p = st.apower;
            if (p !== undefined && p !== null && p < CFG.thresholdW) {
              if (now === null) return;
              if (belowSince === null) belowSince = now;
              if (now - belowSince >= CFG.belowSec) {
                Shelly.call("Switch.Set", { id: CFG.switchId, on: false });
                $ntfyCutoff
                armed = false;
                belowSince = null;
                wasOn = false;
              }
            } else {
              belowSince = null;
            }
          }
        });
        """.trimIndent()
    }

    /** Relit (durée ou null si illimité, seuil) depuis la ligne-marqueur, ou null si absente/illisible. */
    fun parse(code: String): Pair<Int?, Int?>? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith(MARKER) } ?: return null
        val payload = line.trim().removePrefix(MARKER).trim()
        val rows = runCatching { Json.decodeFromString<List<Int?>>(payload) }.getOrNull() ?: return null
        if (rows.size < 2) return null
        return rows[0] to rows[1]
    }
}
