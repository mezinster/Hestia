package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport

/**
 * Génère le script qui notifie la fin naturelle d'un minuteur « Active pour » **sans** coupure
 * sur seuil — seul cas sans aucun script associé sinon (le minuteur natif `toggle_after` suffit à
 * lui seul pour la coupure, rien ne tourne sur l'appareil pour en détecter la fin).
 *
 * **Partagé par appareil physique, pas par canal** (consolidation du 2026-09-29, retour David) :
 * la première version créait un script dédié par canal (`hestia_timer_notify_<canal>`), qui
 * consommait un emplacement de script Shelly (limite de 3 par appareil) pour chaque « Active
 * pour » sans seuil lancé en parallèle — un Strip4 avec 3-4 minuteurs simultanés saturait déjà la
 * limite à lui seul, sans compter planning/bouton/présence. Même principe que
 * [ChargeScriptGenerator.generateSupervisor] : un seul script `hestia_timer_notify` par appareil,
 * qui suit plusieurs canaux dans un tableau de config, mis à jour par `Script.Eval`
 * ([evalUpsertChannel]/[evalRemoveChannel]) tant qu'il tourne déjà.
 */
object TimerNotifyScriptGenerator {

    /** Un seul nom, fixe : un seul script de ce type par appareil physique. */
    const val SCRIPT_NAME = "hestia_timer_notify"

    /** Config d'un canal suivi. [title]/[body] : textes de la notif ntfy à envoyer pour ce canal. */
    data class ChannelConfig(val switchId: Int, val title: String, val body: String)

    /** Littéral JS d'un canal, partagé entre [generateSupervisor] et [evalUpsertChannel]. */
    private fun channelLiteral(c: ChannelConfig): String =
        "{ switchId: ${c.switchId}, title: \"${NtfyScriptSupport.jsString(c.title)}\", " +
            "body: \"${NtfyScriptSupport.jsString(c.body)}\" }"

    /** Littéral JS d'un état de suivi vierge, partagé entre [generateSupervisor] et [evalUpsertChannel]. */
    private const val FRESH_STATE = "{ wasOn: false }"

    /**
     * Premier déploiement du script partagé (aucun script existant, ou existant mais arrêté) —
     * [configs] contient en pratique un seul canal (celui qui démarre son minuteur). Chaque canal
     * est retiré du suivi dès qu'il a notifié (mission ponctuelle, pas besoin de le garder après) ;
     * le script se désactive lui-même dès qu'**aucun** canal n'est plus suivi.
     */
    fun generateSupervisor(configs: List<ChannelConfig>, selfId: Int, ntfyTopic: String? = null): String {
        val cfgArray = configs.joinToString(",\n          ", "[\n          ", "\n        ]") { channelLiteral(it) }
        val notifyCall = NtfyScriptSupport.callDynamicTitleAndBody(ntfyTopic, "title", "body")
        return """
        // Généré par Hestia — notification de fin de minuteur sans coupure sur seuil (plusieurs canaux)
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
        function notify(title, body) { $notifyCall }

        Timer.set(1000, true, function () {
          let kept = [];
          let keptState = [];
          for (let i = 0; i < CFG.length; i++) {
            let cfg = CFG[i];
            let s = STATE[i];
            let st = Shelly.getComponentStatus("switch", cfg.switchId);
            if (!st) { kept.push(cfg); keptState.push(s); continue; }
            if (st.output) s.wasOn = true;
            if (s.wasOn && !st.output) {
              notify(cfg.title, cfg.body);
              continue; // mission de ce canal terminée, pas repoussé dans kept/keptState
            }
            kept.push(cfg);
            keptState.push(s);
          }
          CFG = kept;
          STATE = keptState;
          if (CFG.length === 0) stopSelf();
        });
        """.trimIndent()
    }

    /**
     * Code `Script.Eval` pour ajouter [config], ou le remplacer s'il existe déjà (nouveau
     * minuteur relancé sur un canal déjà suivi — repart avec un état de suivi vierge). Même
     * technique tableau tampon que [ChargeScriptGenerator.evalUpsertChannel] (jamais `.splice()`).
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
