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

    /**
     * Script du minuteur (Manuel/Perso), **par canal** — un seul à la fois par canal, réutilisé
     * par nom. Un appareil multi-canaux (ex. Strip 4) partage un seul moteur de scripts entre
     * tous ses relais : un nom fixe ferait retrouver/écraser le script d'un autre canal du même
     * appareil au lieu du sien (bug vécu en direct avec Bouton physique/Présence, corrigé partout).
     */
    fun scriptName(switchId: Int): String = "hestia_charge_$switchId"

    private const val MARKER = "// hestia_threshold:"

    /**
     * Nom **unique** pour un script de coupure dédié à un planning (jamais partagé, ni entre
     * plannings, ni avec [scriptName]) : un timestamp suffit à éviter toute collision de nom,
     * seul l'id du script (retourné par `Script.Create`) compte ensuite pour Hestia.
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
}
