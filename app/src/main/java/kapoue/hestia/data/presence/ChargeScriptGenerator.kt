package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport

/**
 * Génère le script de **coupure sur seuil de consommation** (« Active pour … + coupe sous X W »).
 *
 * Il complète le minuteur natif (`toggle_after`, qui gère la durée max et le compte à rebours) :
 * il surveille `apower` chaque seconde et **coupe la prise** si la consommation reste sous le seuil
 * pendant [belowSec] secondes d'affilée (fenêtre pour ignorer un creux passager du chargeur).
 *
 * C'est un **one-shot** : dès que la prise est éteinte (coupure conso, fin du minuteur natif, ou
 * action manuelle), le script se **désactive lui-même** (via son propre id) pour ne pas se
 * relancer au redémarrage de la prise.
 */
object ChargeScriptGenerator {

    private const val MARKER = "// hestia_threshold:"

    /**
     * Nom **unique** pour un script de coupure dédié à un planning (jamais partagé, ni entre
     * plannings, ni avec [SUPERVISOR_SCRIPT_NAME]) : un timestamp suffit à éviter toute collision
     * de nom, seul l'id du script (retourné par `Script.Create`) compte ensuite pour Hestia. Un
     * planning donné n'existe qu'une fois : pas concerné par la limite de scripts Shelly qui a
     * motivé le script superviseur ci-dessous.
     */
    fun uniquePlanningScriptName(): String = "hestia_pcut_${System.currentTimeMillis()}"

    /**
     * [ntfyTopic] non nul = notifie via ntfy quand la coupure se déclenche ([ntfyTitle] = nom de
     * la prise, [ntfyBody] = texte). Toujours après `Switch.Set`, jamais avant.
     *
     * [ntfyEndBody] non vide = notifie **aussi** quand le minuteur natif arrive à échéance sans
     * que la coupure sur seuil ne se soit jamais déclenchée (cas « allé au bout des X minutes,
     * jamais sous le seuil ») — sinon ce cas restait silencieux. Détecté sans dépendre du champ
     * `source` (jamais vérifié en direct pour ce cas précis) : on compare l'heure de fin prévue
     * du minuteur natif (`timer_started_at + timer_duration`, lus pendant que la prise est encore
     * allumée) à l'heure actuelle au moment de l'extinction — s'ils coïncident, c'est le minuteur
     * qui a fini sa course, pas un appui bouton ni une extinction manuelle depuis l'appli (les deux
     * couperaient *avant* cette échéance). Sans minuteur natif armé (cas d'un planning, qui n'en
     * pose jamais), cette branche ne se déclenche jamais — la notif de fin de planning existe déjà
     * par ailleurs, via le programme d'extinction lui-même.
     */
    /**
     * [graceSec] : aucune surveillance de la consommation pendant ce délai après l'allumage (0 =
     * surveillance dès la 1ʳᵉ seconde, comportement historique). Utilisé pour le mode « sans
     * limite de durée » : sans minuteur natif en filet de sécurité, une coupure prématurée le
     * temps qu'un appareil commence vraiment à tirer du courant serait plus gênante qu'avec une
     * durée maximale déjà courte. Non exposé pour le mode avec durée, laissé à 0 (inchangé).
     */
    fun generate(
        switchId: Int,
        thresholdW: Int,
        belowSec: Int,
        selfId: Int,
        graceSec: Int = 0,
        ntfyTopic: String? = null,
        ntfyTitle: String = "",
        ntfyBody: String = "",
        ntfyEndBody: String = "",
    ): String {
        val cutoffNtfyStatement = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyBody)
        val endNtfyStatement = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyEndBody)
        return """
        // Généré par Hestia — coupure sur seuil de consommation
        $MARKER$thresholdW
        let CFG = { switchId: $switchId, thresholdW: $thresholdW, belowSec: $belowSec, selfId: $selfId, graceSec: $graceSec };
        let belowSince = null;
        let wasOn = false;
        let lastTimerEnd = null;
        let onSince = null;

        function stopSelf() {
          Shelly.call("Script.SetConfig", { id: CFG.selfId, config: { enable: false } });
          Shelly.call("Script.Stop", { id: CFG.selfId });
        }

        Timer.set(1000, true, function () {
          let st = Shelly.getComponentStatus("switch", CFG.switchId);
          if (!st) return;
          let sys = Shelly.getComponentStatus("sys");
          let now = (sys && sys.unixtime) ? sys.unixtime : null;
          if (st.output) {
            wasOn = true;
            if (onSince === null && now !== null) onSince = now;
            if (st.timer_started_at !== undefined && st.timer_duration !== undefined) {
              lastTimerEnd = st.timer_started_at + st.timer_duration;
            }
          }
          // Prise éteinte après avoir été allumée : fin de mission, on se désactive.
          if (wasOn && !st.output) {
            if (lastTimerEnd !== null && now !== null && now >= lastTimerEnd - 2) {
              $endNtfyStatement
            }
            stopSelf();
            return;
          }
          if (!st.output) return;
          if (CFG.graceSec > 0 && onSince !== null && now !== null && (now - onSince) < CFG.graceSec) return;
          let p = st.apower;
          if (p !== undefined && p !== null && p < CFG.thresholdW) {
            if (now === null) return;
            if (belowSince === null) belowSince = now;
            if (now - belowSince >= CFG.belowSec) {
              Shelly.call("Switch.Set", { id: CFG.switchId, on: false });
              $cutoffNtfyStatement
              stopSelf();
            }
          } else {
            belowSince = null;
          }
        });
        """.trimIndent()
    }

    /** Relit le seuil configuré depuis la ligne-marqueur (même technique que [PresenceScriptGenerator]). */
    fun parseThreshold(code: String): Int? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith(MARKER) } ?: return null
        return line.trim().removePrefix(MARKER).trim().toIntOrNull()
    }

    // --- Script superviseur partagé (2026-08-17/18) : Manuel/Perso/bouton via l'app ---
    //
    // Contrairement à [generate] ci-dessus (mono-canal, un script par planning, jamais partagé
    // par conception — pas concerné par la limite de scripts puisqu'un planning donné n'existe
    // qu'une fois), Manuel/Perso et le bouton physique déclenché depuis l'app peuvent tous les
    // trois vouloir surveiller un seuil sur plusieurs canaux d'un même bloc **en même temps** —
    // un script par canal saturait la limite Shelly de 3 scripts activés par appareil dès que 3
    // minuteurs à seuil tournaient de front. Un seul script `hestia_charge` par appareil physique
    // les gère tous désormais, chacun avec son propre état.
    //
    // [generateSupervisor] ne sert plus qu'au **tout premier déploiement** (aucun script existant,
    // ou existant mais arrêté). Ajouter/retirer un canal sur un script déjà **en cours
    // d'exécution** passe exclusivement par [evalUpsertChannel]/[evalRemoveChannel] via
    // `Script.Eval` (voir `DeviceRepository`) : redéployer le code entier (Stop+PutCode+Start)
    // réinitialiserait la mémoire de **tous** les canaux déjà surveillés, pas seulement celui
    // qu'on ajoute — bug confirmé en direct le 2026-08-18 (deux minuteurs démarrés à 30s
    // d'intervalle ont coupé en même temps, celui démarré en premier ayant vu son décompte
    // repoussé par le redéploiement du second, voir BACKLOG.md). `Script.Eval` sur un script en cours d'exécution
    // laisse sa mémoire strictement intacte pour tout ce qu'on ne touche pas (validé en direct :
    // un compteur qui incrémente en tâche de fond continue sa course sans interruption après une
    // mutation par `Eval` sur une autre variable). Limite acceptée en connaissance de cause : un
    // canal ajouté uniquement par `Eval` ne survit pas à un redémarrage matériel de l'appareil
    // (coupure secteur, mise à jour firmware) — `Script.PutCode` refuse de s'exécuter tant que le
    // script tourne (`-103`), donc impossible de tenir le code source enregistré à jour sans
    // passer par un Stop, qui annulerait justement le bénéfice recherché. Cas rare, sans danger
    // (la prise garde son état, juste sans la protection le temps de relancer le minuteur).

    /** Un seul nom, fixe : un seul script de ce type par appareil physique. */
    const val SUPERVISOR_SCRIPT_NAME = "hestia_charge"

    private const val BELOW_SEC = 60

    /**
     * Config d'un canal suivi par le superviseur. [endBody] vide = pas de notif à la fin du
     * minuteur natif sans coupure sur seuil (cas « sans limite de durée », qui n'a pas de fin
     * naturelle à notifier — [graceSec] non nul en est le signe). [name] sert uniquement au titre
     * des notifications de ce canal.
     */
    data class ChannelConfig(
        val switchId: Int,
        val thresholdW: Int,
        val graceSec: Int,
        val name: String,
        val endBody: String,
    )

    /** Littéral JS d'un canal, partagé entre [generateSupervisor] et [evalUpsertChannel]. */
    private fun channelLiteral(c: ChannelConfig): String =
        "{ switchId: ${c.switchId}, thresholdW: ${c.thresholdW}, graceSec: ${c.graceSec}, " +
            "name: \"${NtfyScriptSupport.jsString(c.name)}\", endBody: \"${NtfyScriptSupport.jsString(c.endBody)}\" }"

    /** Littéral JS d'un état de suivi vierge, partagé entre [generateSupervisor] et [evalUpsertChannel]. */
    private const val FRESH_STATE = "{ belowSince: null, wasOn: false, onSince: null, lastTimerEnd: null }"

    /**
     * Premier déploiement du script partagé (aucun script existant, ou existant mais arrêté) —
     * [configs] contient en pratique un seul canal (celui qui démarre son minuteur). Le script se
     * désactive lui-même dès qu'**aucun** des canaux suivis n'est plus allumé (fin de mission
     * complète) — jamais quand un seul d'entre eux termine.
     */
    fun generateSupervisor(
        configs: List<ChannelConfig>,
        selfId: Int,
        ntfyTopic: String? = null,
        ntfyCutoffBody: String = "",
    ): String {
        val cfgArray = configs.joinToString(",\n          ", "[\n          ", "\n        ]") { channelLiteral(it) }
        val notifyCutoffCall = NtfyScriptSupport.callDynamicTitle(ntfyTopic, "name", ntfyCutoffBody)
        val notifyEndCall = NtfyScriptSupport.callDynamicTitleAndBody(ntfyTopic, "name", "body")
        return """
        // Généré par Hestia — coupure sur seuil de consommation (plusieurs canaux)
        let SELF_ID = $selfId;
        let CFG = $cfgArray;
        let STATE = [];
        for (let i = 0; i < CFG.length; i++) {
          STATE.push($FRESH_STATE);
        }

        function stopSelf() {
          Shelly.call("Script.SetConfig", { id: SELF_ID, config: { enable: false } });
          Shelly.call("Script.Stop", { id: SELF_ID });
        }
        function notifyCutoff(name) { $notifyCutoffCall }
        function notifyEnd(name, body) {
          if (body === "") return;
          $notifyEndCall
        }

        Timer.set(1000, true, function () {
          let anyOn = false;
          let sys = Shelly.getComponentStatus("sys");
          let now = (sys && sys.unixtime) ? sys.unixtime : null;
          for (let i = 0; i < CFG.length; i++) {
            let cfg = CFG[i];
            let s = STATE[i];
            let st = Shelly.getComponentStatus("switch", cfg.switchId);
            if (!st) continue;
            if (st.output) {
              anyOn = true;
              s.wasOn = true;
              if (s.onSince === null && now !== null) s.onSince = now;
              if (st.timer_started_at !== undefined && st.timer_duration !== undefined) {
                s.lastTimerEnd = st.timer_started_at + st.timer_duration;
              }
            }
            // Ce canal vient de s'éteindre après avoir été allumé : fin de sa mission (coupure,
            // minuteur natif arrivé au bout, ou action manuelle) — jamais stopSelf() ici, un autre
            // canal peut encore tourner.
            if (s.wasOn && !st.output) {
              if (s.lastTimerEnd !== null && now !== null && now >= s.lastTimerEnd - 2) {
                notifyEnd(cfg.name, cfg.endBody);
              }
              s.wasOn = false;
              continue;
            }
            if (!st.output) continue;
            if (cfg.graceSec > 0 && s.onSince !== null && now !== null && (now - s.onSince) < cfg.graceSec) continue;
            let p = st.apower;
            if (p !== undefined && p !== null && p < cfg.thresholdW) {
              if (now === null) continue;
              if (s.belowSince === null) s.belowSince = now;
              if (now - s.belowSince >= $BELOW_SEC) {
                Shelly.call("Switch.Set", { id: cfg.switchId, on: false });
                notifyCutoff(cfg.name);
                s.belowSince = null;
              }
            } else {
              s.belowSince = null;
            }
          }
          // Plus aucun canal suivi n'est allumé : mission complète, on se désactive entièrement.
          if (!anyOn) stopSelf();
        });
        """.trimIndent()
    }

    /**
     * Code `Script.Eval` pour ajouter [config], ou le remplacer s'il existe déjà (nouveau
     * minuteur relancé sur un canal déjà suivi — repart avec un état de suivi vierge, ce qui est
     * le comportement voulu). Reconstruit `CFG`/`STATE` par un tableau tampon (`for` + `push`),
     * jamais `.splice()` — seule technique déjà éprouvée en direct dans ce projet (voir
     * BACKLOG.md), la même prudence que dans le reste des scripts générés.
     */
    fun evalUpsertChannel(config: ChannelConfig): String = """
        (function () {
          let kept = [];
          let keptState = [];
          for (let i = 0; i < CFG.length; i++) {
            if (CFG[i].switchId !== ${config.switchId}) { kept.push(CFG[i]); keptState.push(STATE[i]); }
          }
          kept.push(${channelLiteral(config)});
          keptState.push($FRESH_STATE);
          CFG = kept;
          STATE = keptState;
        })();
    """.trimIndent()

    /** Code `Script.Eval` pour retirer le canal [switchId] du suivi ; ne fait rien s'il est absent. */
    fun evalRemoveChannel(switchId: Int): String = """
        (function () {
          let kept = [];
          let keptState = [];
          for (let i = 0; i < CFG.length; i++) {
            if (CFG[i].switchId !== $switchId) { kept.push(CFG[i]); keptState.push(STATE[i]); }
          }
          CFG = kept;
          STATE = keptState;
        })();
    """.trimIndent()
}
