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

- **Programmation d'une plage horaire future** (ex. « 2 h de charge de 15 h à 17 h » alors qu'il
  est 11 h). À faire via le composant **Schedule natif de Shelly** (cron), donc autonome sur
  l'appareil — fidèle au principe « pas de scheduler côté Android ». Beau candidat pour un lot
  post-V1 dédié.
- **Notifications** : fin de minuterie, début/fin de programmation, « charge terminée » (bascule
  vers une consommation faible, ex. Mac ou scooter). ⚠️ Hors V1 (CLAUDE.md : pas de notifications
  en V1 ; nécessite la permission notifications + un mécanisme de veille).
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
