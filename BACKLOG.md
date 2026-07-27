# Backlog — Hestia

Points relevés en cours de route, à traiter dans un lot ultérieur (pas des bugs bloquants).
Dernière mise à jour : 2026-07-27 (après le lot Coupure sur seuil, roulette de valeurs).

## Finitions (petits polissages)

- **Attribuer une cause à chaque allumage/extinction dans le journal** (manuel, minuteur,
  planning, mode présence). Aujourd'hui « Allumé »/« Éteint » n'indiquent pas l'origine. Piège :
  les bascules **autonomes** (planning, présence, fin de minuteur) ne sont pas observées à
  l'instant où elles surviennent (appli fermée), seulement au relevé suivant → la cause doit être
  **inférée** de l'état connu (créneau de planning actif, minuteur en cours, script déployé…).
  Faisable mais mérite son propre lot. Englobe l'ancien point « distinguer fin de minuteur vs
  extinction manuelle ». À noter : le worker de notifications sait déjà faire une partie de cette
  inférence (mémo `PendingTimer`, vérification du script de seuil) — il y a de la logique à
  mutualiser.

## Écarté

- **Authentification de l'appareil (mot de passe Shelly).** Envisagé (saisie à l'ajout + client
  RPC en digest auth + stockage sécurisé), **écarté le 2026-07-26** : David n'en met pas sur son
  réseau privé. À reconsidérer seulement si des utilisateurs F-Droid le demandent.
- **Remplacer `material-icons-extended` par les icônes réellement utilisées** (10 sur les 17
  icônes de l'app n'existent que dans ce module, les 7 autres sont dans le module « core »).
  **Écarté le 2026-07-27** : le poids qu'il posait est déjà réglé par R8 (47 Mo → 3,6 Mo en
  release) ; l'extraction manuelle des tracés vectoriels (tentée par décompilation) s'est révélée
  peu fiable sur les icônes à courbes (Wifi, Sensors, Lightbulb), et un rendu cassé ne serait pas
  détectable sans capture d'écran après build. À reconsidérer seulement si le poids redevient un
  problème réel.

## Fonctionnalités futures

- **Traduction anglaise** (déjà prévue au CLAUDE.md) : adaptation à la langue du système
  (anglais par défaut, français si l'appareil est en français). Au-delà des `strings.xml`,
  attention aux **formats codés « à la française »** qui ne se traduisent pas tout seuls :
  `formatTimeRange` produit `9h00 - 11h00` (format horaire français en dur) et le worker de
  notifications formate ses heures en `8h30` → à localiser. Les nombres, eux, sont déjà corrects :
  `formatPower` force le point (choix produit) et `formatCountdown` est neutre.
- **Audit des fonctions RPC de la prise non gérées** par Hestia (mesure d'énergie détaillée,
  métriques cumulées, etc.).

## Fait — pour mémoire

Points sortis du backlog, avec ce qui a été tranché :

- **Rouleaux crantés** (minuteur et horaires de présence/planning) : `TimeWheelPicker` maison,
  défilement infini + retour haptique, sans dépendance.
- **Relire les horaires de présence depuis l'appareil** (c'était un défaut de correction : un
  second téléphone affichait des valeurs par défaut et pouvait écraser la programmation).
  Résolu par la **piste (a)** — marqueur `// hestia_windows:[[start,end,margin],…]` en tête du
  script généré, relu via `Script.GetCode`. La configuration voyage avec le script, Hestia ne
  stocke rien.
- **Planning** via le composant `Schedule` natif, **y compris le passage par-dessus minuit**
  (l'extinction est programmée sur les jours décalés au lendemain) et l'indicateur sur la tuile.
- **Coupure automatique sur seuil de consommation** : script `hestia_charge` qui surveille
  `apower` et coupe le relais après 60 s sous le seuil, puis s'auto-désactive.
- **Puissance instantanée sur la tuile** et **marqueur « Mode présence »** sur le Tableau.
- **Notifications** : la tension architecturale relevée ici a été tranchée en faveur du
  **100 % local** — WorkManager (~15 min, délai assumé et annoncé dans l'UI) plutôt qu'un webhook
  sortant type ntfy, qui aurait fait quitter le réseau local à la prise. Opt-in, désactivé par
  défaut, `POST_NOTIFICATIONS` demandée à l'activation seulement. Notifie les **bornes** de
  programmation (une seule notification par borne), la fin d'un minuteur et la coupure sur seuil ;
  jamais une action manuelle.
- **Écran de configuration de présence** : supprimé (la présence se gère depuis l'écran de
  détail, comme le planning), ce qui rend caduque l'ancienne demande de confirmation
  « modifications non enregistrées ». La route de navigation `presence` associée, restée morte
  dans `Destinations.kt` après cette suppression, a été retirée au lot Nettoyage (2026-07-27).
- **Vérification manuelle du firmware** : section « Mise à jour du firmware » sur l'écran de
  détail, bouton « Vérifier » qui interroge `Shelly.CheckForUpdate` — jamais en tâche de fond,
  seule action du projet qui sort du réseau local. Bêta signalée mais jamais installable.
  Tranché : pas de notification de mise à jour disponible (contrairement à ce qu'envisageait
  l'ancienne entrée de ce backlog) — la vérification reste ponctuelle, à la main de l'utilisateur.
- **Coupure sur seuil, ergonomie** : case à cocher remplacée par un interrupteur (désactivé par
  défaut, même style que Notifications), et saisie libre du seuil remplacée par une roulette sur
  des valeurs prédéfinies (5, 10, 20, 30, 40, 50 W, défaut 10) via le nouveau `ValueWheelPicker`,
  qui généralise `TimeWheelPicker` à une liste de valeurs arbitraire.
