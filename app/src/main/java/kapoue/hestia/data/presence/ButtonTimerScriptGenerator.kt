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
 * **Un seul script par appareil physique, pas par canal** (2026-08-17, script « superviseur ») :
 * le moteur de scripts Shelly n'autorise que **3 scripts activés simultanément par appareil**
 * (erreur RPC `-108` au-delà) — un bloc à 4 canaux avec un minuteur bouton sur chacun aurait
 * besoin de 4 scripts en permanence, saturant la limite à lui seul et bloquant silencieusement
 * toute autre fonctionnalité à base de script (seuil, présence, notifs) sur le bloc entier, quel
 * que soit le canal. Un seul script surveille donc désormais tous les canaux configurés à la
 * fois, chacun avec son propre état (armé, minuteur, seuil) — totalement indépendant des autres.
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

    /** Un seul nom, fixe : un seul script de ce type par appareil physique désormais. */
    const val SCRIPT_NAME = "hestia_button_timer"

    private const val MARKER = "// hestia_button_timer:"
    private const val BELOW_SEC = 60
    /** Grâce avant surveillance quand [ChannelConfig.durationSec] est null (sans limite de durée). */
    private const val UNLIMITED_GRACE_SEC = 15 * 60

    /**
     * Config d'un canal surveillé par le script. [durationSec] null = sans limite de durée (la
     * prise reste allumée jusqu'à la coupure sur seuil, [thresholdW] alors obligatoire — imposé
     * côté appelant). [name] sert uniquement au titre des notifications ntfy de ce canal.
     */
    data class ChannelConfig(
        val switchId: Int,
        val durationSec: Int?,
        val thresholdW: Int?,
        val name: String,
    )

    /**
     * [configs] : un élément par canal configuré (les autres canaux de l'appareil, absents de la
     * liste, ne sont pas concernés). [ntfyTopic] non nul = notifie via ntfy à la fin
     * ([ntfyEndBody]) et à une coupure sur seuil ([ntfyCutoffBody]) — texte générique, identique
     * pour tous les canaux, seul le titre (nom de la prise) varie, résolu à l'exécution. Un appui
     * bouton qui annule le minuteur en cours (source de nouveau un appui bouton au moment de
     * l'extinction, voir `isButtonSource`) ne notifie jamais — c'est une action manuelle délibérée.
     */
    fun generate(
        configs: List<ChannelConfig>,
        ntfyTopic: String? = null,
        ntfyEndBody: String = "",
        ntfyCutoffBody: String = "",
    ): String {
        val marker = configs.joinToString(",", "[", "]") {
            "[${it.switchId},${it.durationSec ?: "null"},${it.thresholdW ?: "null"}]"
        }
        val cfgArray = configs.joinToString(",\n          ", "[\n          ", "\n        ]") { c ->
            val graceSec = if (c.durationSec == null) UNLIMITED_GRACE_SEC else 0
            val durationLiteral = c.durationSec?.toString() ?: "null"
            val thresholdLiteral = c.thresholdW?.toString() ?: "null"
            "{ switchId: ${c.switchId}, durationSec: $durationLiteral, thresholdW: $thresholdLiteral, " +
                "belowSec: $BELOW_SEC, graceSec: $graceSec, name: \"${NtfyScriptSupport.jsString(c.name)}\" }"
        }
        val notifyEnd = NtfyScriptSupport.callDynamicTitle(ntfyTopic, "name", ntfyEndBody)
        val notifyCutoff = NtfyScriptSupport.callDynamicTitle(ntfyTopic, "name", ntfyCutoffBody)
        return """
        // Généré par Hestia — minuteur déclenché par le bouton physique (plusieurs canaux)
        $MARKER$marker
        let CFG = $cfgArray;
        let STATE = [];
        for (let i = 0; i < CFG.length; i++) {
          STATE.push({ armed: false, wasOn: false, belowSince: null, onSince: null });
        }

        // La valeur exacte de "source" pour un appui bouton varie selon le modèle de prise
        // (ex. "button" sur Plug M, "short_push" sur Strip 4) — accepte les variantes plausibles.
        function isButtonSource(src) {
          return src === "button" || src === "short_push" || src === "long_push" || src === "double_push" || src === "triple_push";
        }

        function notifyEnd(name) { $notifyEnd }
        function notifyCutoff(name) { $notifyCutoff }

        Timer.set(1000, true, function () {
          for (let i = 0; i < CFG.length; i++) {
            let cfg = CFG[i];
            let s = STATE[i];
            let st = Shelly.getComponentStatus("switch", cfg.switchId);
            if (!st) continue;

            if (!s.armed) {
              // En veille : un appui qui vient d'allumer arme le minuteur par-dessus (ou, sans
              // durée, laisse simplement l'allumage natif du bouton tel quel).
              if (st.output && !s.wasOn && isButtonSource(st.source)) {
                s.armed = true;
                s.belowSince = null;
                s.onSince = null;
                if (cfg.durationSec !== null) {
                  Shelly.call("Switch.Set", { id: cfg.switchId, on: true, toggle_after: cfg.durationSec });
                }
              }
              s.wasOn = st.output;
              continue;
            }

            if (s.wasOn && !st.output) {
              // Éteinte pendant que le minuteur tournait : fin naturelle, coupure, ou action
              // manuelle. Un nouvel appui bouton pour annuler ne notifie jamais (délibéré).
              if (!isButtonSource(st.source)) {
                notifyEnd(cfg.name);
              }
              s.armed = false;
              s.belowSince = null;
              s.wasOn = false;
              continue;
            }
            s.wasOn = st.output;

            let sys = Shelly.getComponentStatus("sys");
            let now = (sys && sys.unixtime) ? sys.unixtime : null;
            if (s.onSince === null && now !== null) s.onSince = now;
            if (cfg.graceSec > 0 && s.onSince !== null && now !== null && (now - s.onSince) < cfg.graceSec) continue;

            if (cfg.thresholdW !== null && st.output) {
              let p = st.apower;
              if (p !== undefined && p !== null && p < cfg.thresholdW) {
                if (now === null) continue;
                if (s.belowSince === null) s.belowSince = now;
                if (now - s.belowSince >= cfg.belowSec) {
                  Shelly.call("Switch.Set", { id: cfg.switchId, on: false });
                  notifyCutoff(cfg.name);
                  s.armed = false;
                  s.belowSince = null;
                  s.wasOn = false;
                }
              } else {
                s.belowSince = null;
              }
            }
          }
        });
        """.trimIndent()
    }

    /** Relit (canal, durée ou null si illimité, seuil) pour chaque canal configuré, ou null si illisible. */
    fun parse(code: String): List<Triple<Int, Int?, Int?>>? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith(MARKER) } ?: return null
        val payload = line.trim().removePrefix(MARKER).trim()
        val rows = runCatching { Json.decodeFromString<List<List<Int?>>>(payload) }.getOrNull() ?: return null
        return rows.mapNotNull { r ->
            if (r.size < 3 || r[0] == null) return@mapNotNull null
            Triple(r[0]!!, r[1], r[2])
        }
    }
}
