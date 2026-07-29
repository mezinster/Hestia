package kapoue.hestia.data.presence

import kapoue.hestia.data.notifications.NtfyScriptSupport

/**
 * Script minimal, déployé **uniquement quand ntfy est actif**, pour notifier la fin naturelle
 * d'un minuteur « Active pour » **sans** coupure sur seuil — ce cas n'a autrement aucun script
 * associé (le minuteur natif `toggle_after` suffit à lui seul, rien ne tourne sur l'appareil pour
 * en détecter la fin). One-shot comme [ChargeScriptGenerator] : se désactive dès que la prise
 * s'éteint, quelle que soit la cause (fin de minuteur, bouton physique, action manuelle).
 */
object TimerNotifyScriptGenerator {

    const val SCRIPT_NAME = "hestia_timer_notify"

    fun generate(switchId: Int, selfId: Int, ntfyTopic: String, ntfyTitle: String, ntfyBody: String): String {
        val ntfyStatement = NtfyScriptSupport.call(ntfyTopic, ntfyTitle, ntfyBody)
        return """
        // Généré par Hestia — notification de fin de minuteur (sans coupure sur seuil)
        let CFG = { switchId: $switchId, selfId: $selfId };
        let wasOn = false;

        Timer.set(1000, true, function () {
          let st = Shelly.getComponentStatus("switch", CFG.switchId);
          if (!st) return;
          if (st.output) wasOn = true;
          if (wasOn && !st.output) {
            $ntfyStatement
            Shelly.call("Script.SetConfig", { id: CFG.selfId, config: { enable: false } });
            Shelly.call("Script.Stop", { id: CFG.selfId });
          }
        });
        """.trimIndent()
    }
}
