# Backlog — Hestia

Points relevés en cours de route, à traiter dans un lot ultérieur (pas des bugs bloquants).

## Finitions (petits polissages)

- **Sélecteur « Perso » à rouleaux crantés** (minuteur). La version actuelle est un simple
  double champ heures/minutes (fonctionnel). Le SPEC prévoit la métaphore « minuterie
  mécanique » avec deux rouleaux à faire défiler au doigt + retour haptique. Implémentation
  maison (sans dépendance).
- **Sélecteur d'heures de présence** : simples champs h/min pour l'instant → à passer en vrai
  sélecteur d'heure.
- **Confirmation « modifications non enregistrées »** sur l'écran de config présence si l'on
  quitte après avoir changé les heures sans déployer (faibles enjeux).
- **Distinguer l'extinction « fin de minuteur » de l'extinction manuelle** dans le journal
  (les deux apparaissent « Éteint »). Heuristique possible : minuteur actif au relevé précédent.
- **⚠️ Relire les horaires de présence depuis l'appareil** (défaut de correction, pas confort).
  Les horaires ne sont stockés que dans la base locale du téléphone **qui a déployé** le script.
  Un second téléphone affiche donc les valeurs par défaut et, pire, **peut redéployer un script
  avec d'autres horaires en écrasant silencieusement les premiers**. Contraire au principe
  « Hestia lit l'état réel, ne suppose jamais » (CLAUDE.md) — c'est aujourd'hui la seule fonction
  qui l'enfreint. Deux pistes :
  a) **marqueur lisible dans le script généré** (ex. `// hestia:{"startHour":17,…}` en tête),
     relu via `Script.GetCode` : la configuration voyage avec le script, rien à stocker à côté,
     impossible qu'elle se désynchronise ;
  b) **KVS de l'appareil** (`KVS.Set` / `KVS.Get`), prévu pour ça mais ajoute une surface RPC.
  Préférence pour (a). Prévoir le cas des scripts **déjà déployés sans marqueur** : afficher
  « horaires inconnus » plutôt que des valeurs fausses, et proposer de redéployer.
  À noter : le futur **Planning** n'aura pas ce défaut, puisqu'il s'appuiera sur le composant
  `Schedule` natif, relisible directement via `Schedule.List`.
- **Afficher la puissance instantanée sur la tuile**, à côté de l'icône en haut (ex. `12 W`).
  `apower` est **déjà parsé** dans `SwitchStatusResult` et arrive à chaque rafraîchissement :
  aucun appel réseau supplémentaire, c'est purement de l'affichage. À conditionner sur
  `hasPowerMetering`. À trancher : format (W, arrondi, passage en kW ?), ce qu'on affiche quand
  la prise est éteinte ou tire 0 W, et la place disponible à côté du numéro et de l'icône.
- **Signaler la simulation de présence sur le Tableau.** Une prise pilotée par le script affiche
  « Repos » / « Actif » comme n'importe quelle autre : rien n'indique qu'un script la manœuvre,
  et les bascules spontanées peuvent passer pour un comportement fantôme. L'information n'existe
  aujourd'hui que sur l'écran de détail. Prévoir un marqueur sur la tuile (picto + libellé texte,
  jamais la couleur seule — cf. CLAUDE.md).

## Fonctionnalités futures (post-V1)

- **Planning — passage par-dessus minuit** (charge de nuit / heures creuses, ex. 22h → 6h). Le
  planning de journée (fin après début) est fait en 1.1.0 via le composant **Schedule natif**.
  Le créneau qui traverse minuit reste à traiter : propre pour « tous les jours », ambigu avec
  des jours précis (la nuit du vendredi déborde sur le week-end) → à concevoir à part.
- **Planning — indicateur sur la tuile du Tableau** (2ᵉ incrément) : « Planning » + horaires en
  orange, comme le mode présence. Demande une lecture `Schedule.List` par appareil au relevé.
- **Coupure automatique sur seuil de conso (« fin de charge »)** ⭐. Un **script déployé sur la
  prise** (comme la simulation de présence) surveille `apower` et **coupe le relais** quand la
  puissance reste sous un seuil réglable pendant N minutes. Scooter, vélo, Mac, téléphone : la
  prise se coupe seule une fois la charge finie. **Fidèle à l'architecture** : autonome sur
  l'appareil, aucun cloud, aucun composant Android en tâche de fond. Réutilise le mécanisme de
  génération/déploiement de script déjà en place. Seuil + durée à saisir dans l'app, relus depuis
  la prise. **Le meilleur candidat des idées « scripts ».**
- **Notifications** quand la prise passe ON/OFF (fin de minuterie, planning, « charge terminée »).
  ⚠️ **Tension architecturale à trancher AVANT de s'y lancer.** Hestia n'a **aucun composant en
  tâche de fond** (WorkManager retiré exprès, cf. permissions F-Droid). Pour notifier un
  changement d'état, le téléphone devrait **surveiller la prise en arrière-plan** → réintroduire
  un poller (WorkManager/service au premier plan) + permission `POST_NOTIFICATIONS` + coût
  batterie : contraire à « pas de scheduler Android, permissions minimales ». L'autre voie
  (webhook sortant de la prise vers un service push type ntfy) **quitte le réseau local** →
  contraire à « 100 % local ». C'est **la seule fonctionnalité demandée qui heurte les principes
  du projet** : à assumer explicitement (opt-in, compromis documenté) ou à écarter. La coupure
  auto ci-dessus fait déjà **l'action** de façon autonome ; seul le « prévenir » pose problème.
- **Vérification du firmware de la prise** (via `Shelly.CheckForUpdate`) + notif si mise à jour
  dispo. ⚠️ Nuance à trancher : cette méthode fait **contacter le serveur de Shelly par
  l'appareil** — à confronter au principe « 100 % local » avant de l'implémenter.
- **Consommation en temps réel sur le Tableau** (`apower`), pour les appareils avec mesure
  (`hasPowerMetering`).
- **Audit des fonctions RPC de la prise non gérées** par Hestia (mesure d'énergie détaillée,
  planning, etc.).
- **Traduction anglaise** (déjà prévue au CLAUDE.md) : adaptation à la langue du système
  (anglais par défaut, français si l'appareil est en français). Au-delà des `strings.xml`,
  attention aux **formats codés « à la française »** qui ne se traduisent pas tout seuls :
  `formatTimeRange` produit `9h00 - 11h00` (format horaire français en dur) → à localiser
  (`9:00 AM` ou format régional). Les nombres, eux, sont déjà corrects : `formatPower` force le
  point (choix produit) et `formatCountdown` est neutre.
