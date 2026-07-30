package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport
import kotlinx.serialization.json.Json

/**
 * Script **persistant** : veille en permanence un appui sur le bouton physique et arme un
 * minuteur natif par-dessus (avec coupure sur seuil optionnelle), sans toucher au comportement
 * natif du bouton — repose sur le champ `source` de `Switch.GetStatus` (`"button"`), pas sur un
 * composant Input (absent sur ce modèle, validé en direct : pas d'appui long/double possible sur
 * ce matériel, le bouton est câblé en dur au relais).
 *
 * Contrairement aux autres scripts de coupure (déployés une fois, se désactivent après usage),
 * celui-ci ne se supprime jamais lui-même : après chaque cycle (fin naturelle, coupure sur
 * seuil, ou nouvel appui pendant qu'il tourne), il se remet en veille pour l'appui suivant.
 * `enable:true` (contrairement au script de coupure d'un planning) : doit redémarrer seul après
 * un redémarrage de l'appareil, la fonctionnalité ne doit pas se désactiver silencieusement.
 */
object ButtonTimerScriptGenerator {

    const val SCRIPT_NAME = "hestia_button_timer"

    private const val MARKER = "// hestia_button_timer:"
    private const val BELOW_SEC = 60

    /**
     * [ntfyTopic] non nul = notifie via ntfy à la fin ([ntfyEndBody]) et à une coupure sur seuil
     * ([ntfyCutoffBody]). Un appui bouton qui annule le minuteur en cours (source à nouveau
     * "button" au moment de l'extinction) ne notifie jamais — c'est une action manuelle délibérée.
     */
    fun generate(
        switchId: Int,
        durationSec: Int,
        thresholdW: Int?,
        ntfyTopic: String? = null,
        ntfyTitle: String = "",
        ntfyEndBody: String = "",
        ntfyCutoffBody: String = "",
    ): String {
        val marker = "[$durationSec,${thresholdW ?: "null"}]"
        val thresholdLiteral = thresholdW?.toString() ?: "null"
        val ntfyEnd = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyEndBody)
        val ntfyCutoff = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyCutoffBody)
        return """
        // Généré par Hestia — minuteur déclenché par le bouton physique
        $MARKER$marker
        let CFG = { switchId: $switchId, durationSec: $durationSec, thresholdW: $thresholdLiteral, belowSec: $BELOW_SEC };
        let armed = false;
        let wasOn = false;
        let belowSince = null;

        Timer.set(1000, true, function () {
          let st = Shelly.getComponentStatus("switch", CFG.switchId);
          if (!st) return;

          if (!armed) {
            // En veille : un appui qui vient d'allumer arme le minuteur par-dessus.
            if (st.output && !wasOn && st.source === "button") {
              armed = true;
              belowSince = null;
              Shelly.call("Switch.Set", { id: CFG.switchId, on: true, toggle_after: CFG.durationSec });
            }
            wasOn = st.output;
            return;
          }

          if (wasOn && !st.output) {
            // Éteinte pendant que le minuteur tournait : fin naturelle, coupure, ou action
            // manuelle. Un nouvel appui bouton pour annuler ne notifie jamais (délibéré).
            if (st.source !== "button") {
              $ntfyEnd
            }
            armed = false;
            belowSince = null;
            wasOn = false;
            return;
          }
          wasOn = st.output;

          if (CFG.thresholdW !== null && st.output) {
            let p = st.apower;
            if (p !== undefined && p !== null && p < CFG.thresholdW) {
              let sys = Shelly.getComponentStatus("sys");
              if (!sys || !sys.unixtime) return;
              let now = sys.unixtime;
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

    /** Relit (durée, seuil) depuis la ligne-marqueur, ou null si absente/illisible. */
    fun parse(code: String): Pair<Int, Int?>? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith(MARKER) } ?: return null
        val payload = line.trim().removePrefix(MARKER).trim()
        val rows = runCatching { Json.decodeFromString<List<Int?>>(payload) }.getOrNull() ?: return null
        if (rows.size < 2) return null
        val duration = rows[0] ?: return null
        return duration to rows[1]
    }
}
