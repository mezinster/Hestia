package kapoue.hestia.data.presence

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

    const val SCRIPT_NAME = "hestia_charge"

    fun generate(switchId: Int, thresholdW: Int, belowSec: Int, selfId: Int): String = """
        // Généré par Hestia — coupure sur seuil de consommation
        let CFG = { switchId: $switchId, thresholdW: $thresholdW, belowSec: $belowSec, selfId: $selfId };
        let belowSince = null;
        let wasOn = false;

        function stopSelf() {
          Shelly.call("Script.SetConfig", { id: CFG.selfId, config: { enable: false } });
          Shelly.call("Script.Stop", { id: CFG.selfId });
        }

        Timer.set(1000, true, function () {
          let st = Shelly.getComponentStatus("switch", CFG.switchId);
          if (!st) return;
          if (st.output) wasOn = true;
          // Prise éteinte après avoir été allumée : fin de mission, on se désactive.
          if (wasOn && !st.output) { stopSelf(); return; }
          if (!st.output) return;
          let p = st.apower;
          if (p !== undefined && p !== null && p < CFG.thresholdW) {
            let sys = Shelly.getComponentStatus("sys");
            if (!sys || !sys.unixtime) return;
            let now = sys.unixtime;
            if (belowSince === null) belowSince = now;
            if (now - belowSince >= CFG.belowSec) {
              Shelly.call("Switch.Set", { id: CFG.switchId, on: false });
              stopSelf();
            }
          } else {
            belowSince = null;
          }
        });
    """.trimIndent()
}
