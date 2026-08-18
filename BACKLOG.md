# Backlog — Hestia

Points relevés en cours de route, à traiter dans un lot ultérieur (pas des bugs bloquants).
Dernière mise à jour : 2026-08-17 (refonte du Tableau, lots 1-4/5 ; découverte de la limite de
3 scripts Shelly par appareil, chantier « script superviseur » à venir).

## Fait — en cours (à surveiller)

- **Traduction anglaise** : `values/strings.xml` (défaut) est désormais l'anglais, `values-fr/`
  porte le français — Android choisit tout seul selon la langue système. Deux formats horaires
  codés « à la française » corrigés au passage (`formatTimeRange`, les horaires des
  notifications) via un nouveau `formatClockTime()` locale-aware (`DateTimeFormatter.
  ofLocalizedTime`). Les étiquettes fixes du rapport de diagnostic (« Application :, Android :,
  Thème : »…) déplacées en ressources au passage — elles ne l'étaient pas.
  **Scope volontairement laissé de côté** : les messages individuels écrits via
  `DiagnosticLogger.info/warn/error(...)` (une quinzaine de points d'appel, ex. "Bascule
  192.168.1.96#0 → true") restent en français uniquement. Ce journal est accessible en
  production (5 appuis sur le titre du Tableau) et partageable — un utilisateur anglophone qui
  le partagerait verrait donc des lignes de log en français au milieu d'un rapport sinon
  traduit. Accepté pour l'instant vu le volume de points d'appel dispersés ; à traiter si ça
  pose un problème réel en usage. Les données de démo (noms des appareils fictifs, "Démo" comme
  modèle) restent aussi en français, pour ne pas casser les captures d'écran F-Droid actuelles.

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
- **Journal d'activité par appareil (« Dernière activité »).** Retiré intégralement le
  2026-07-27 : entité `ActivationLog`, DAO, section d'écran, formatage. Tenté d'y attribuer une
  cause fiable (bouton, minuteur, planning, présence) via le champ `source` de l'appareil —
  fonctionnait bien app ouverte, mais un test délibéré (app tuée pendant tout le cycle) a confirmé
  la limite de fond : sans tâche de fond permanente, aucune observation n'a lieu, et si l'état
  final rejoint l'état de départ pendant l'absence, même le passage du worker de notifications
  (~15 min, lui-même limité aux utilisateurs ayant activé les notifications) ne voit rien à
  journaliser. Storer l'historique **sur la prise** (KVS) a été envisagé puis écarté : ne
  couvrirait que les bascules déclenchées par les scripts Hestia (présence, coupure sur seuil),
  jamais le planning natif (`Schedule`) ni le bouton physique — une complexité réelle pour une
  couverture qui resterait partielle. La colonne `cause` ajoutée à la table (migration v4→v5) est
  retirée avec la table entière (migration v5→v6, `DROP TABLE`) ; la colonne vestige
  `lastKnownOutput` sur `devices` reste en base (retrait par `DROP COLUMN` jugé trop risqué sur
  les versions de SQLite embarquées par Android 11, minSdk du projet) mais n'est plus utilisée.

## En cours de traitement

Retenus le 2026-08-14 pour ce lot, pas encore attaqués :

- Ajout d'un appareil neuf sans sortir de Hestia (provisioning Wi-Fi direct depuis l'appli) : la
  prise se connecte temporairement à son propre point d'accès, envoie le Wi-Fi cible via
  `WiFi.SetConfig`, puis relit elle-même l'IP obtenue — sans scan réseau ni lecture de MAC (deux
  choses qu'une appli Android normale ne peut de toute façon plus faire proprement).
- Fusion de Planning et Simulation de présence (switch « simuler une présence », fait apparaître
  la marge aléatoire) — chantier à part, plus invasif, prévu après le reste pour ne pas fragiliser
  une base qui vient de beaucoup bouger.
- **Refonte du Tableau, lot 5/5 — mise en avant du programme en cours** : codé le 2026-08-17
  (voir détail en § Fait), mais **pas encore testé/validé** — retour direct au test du Lot 4 à la
  place. Gardé en l'état, à reprendre au prochain test complet.
- **Scripts Shelly : limite dure de 3 activés simultanément par appareil, découverte le
  2026-08-17** — chantier majeur, priorité haute. Diagnostiqué en direct sur le Strip 4 (erreur
  RPC `-108 "Reached the maximum 3 of enabled scripts"`) en creusant le seuil qui ne coupait pas
  (Lot 4) : le moteur de scripts Shelly est partagé par **appareil physique**, pas par canal — un
  bloc à 4 canaux avec un minuteur bouton configuré sur 3 canaux ou plus (usage tout à fait normal
  d'une multiprise) sature la limite en permanence, bloquant silencieusement toute autre
  fonctionnalité à base de script sur *n'importe quel* canal du bloc (seuil, présence, notif de
  fin) — le minuteur natif continue de s'afficher normalement dans l'app car il ne dépend pas du
  script, ce qui masque complètement le problème côté utilisateur.
  Concept de correctif **validé en direct par test réel** (script de preuve `hestia_test_multi`
  sur le Strip 4, lisant l'état du canal 0 et pilotant le canal 2 depuis un seul script — confirmé
  fonctionnel, `source:"loopback"` observé sur le canal piloté). Direction retenue : **un seul
  script « superviseur » par fonctionnalité et par appareil physique**, gérant en interne tous ses
  canaux, au lieu d'un script par canal — ramène la consommation de 4 scripts (pire cas actuel) à
  1, quel que soit le nombre de canaux. Implique de revoir les générateurs de script (liste de
  configs par canal au lieu d'un `switchId` unique) et la façon dont `DeviceRepository` lit/écrit
  la config d'un canal *à l'intérieur* d'un script partagé. Migration nécessaire pour les scripts
  déjà déployés chez l'utilisateur (à reconfigurer une fois le nouveau système en place).
  **Élargissement identifié en discutant** : ce même principe « par appareil physique, pas par
  canal » s'applique déjà, de façon plus ou moins propre, à d'autres fonctions non liées aux
  scripts — la vérification de mise à jour firmware et le redémarrage de l'appareil, aujourd'hui
  dupliqués à l'identique sur l'écran Détail de chacun des canaux d'un même bloc (confirmé dans le
  code, `DetailScreen` étant par canal). L'idée est de profiter de cette refonte pour clarifier
  partout dans Hestia ce qui relève du canal individuel vs de l'appareil physique, pas seulement
  pour les scripts.
  **Lot A (minuteur bouton) livré et validé en test réel le 2026-08-17** : un seul script
  `hestia_button_timer` par appareil (fini le suffixe par canal), config = liste `[[switchId,
  durée,seuil],...]`, état JS par canal (tableau `STATE`, pas de variables globales). Migration
  automatique et transparente des anciens scripts par-canal vers le nouveau format confirmée sur
  le Plug M (l'ancien script disparaît bien après lecture/écriture, sans action utilisateur).
  Comportement fonctionnel confirmé sur le Strip 4 : deux canaux avec détection de seuil,
  notifications de fin reçues après ~1 min sous le seuil, sur plusieurs canaux à la fois via un
  seul script. Signature de `DeviceRepository.getButtonTimerConfig`/`setButtonTimer` inchangée,
  aucune UI à toucher. Prudence conservée : `Array.map()` évité dans le JS généré au profit d'une
  boucle `for` + `push()`, seule technique déjà éprouvée en production.
  **Lot B (coupure sur seuil `hestia_charge`) — bug trouvé en test réel le 2026-08-18, corrigé le
  même jour, pas encore validé sur le terrain.** Une première version (même principe que le Lot A :
  redéploie tout le script — `Stop`+réécriture+`Start` — à chaque canal ajouté/retiré) a révélé un
  vrai bug au premier test sur le Strip 4 : minuteur avec seuil lancé sur la prise 1, puis sur la
  prise 2 30s plus tard → **les deux ont coupé en même temps**, la prise 1 ayant tourné 1min30 au
  lieu de 1min. Diagnostic confirmé par un relevé toutes les 5s (`Switch.GetStatus` + contenu du
  script) : le redéploiement déclenché par le démarrage de la prise 2 réinitialise la mémoire
  interne (compteur « sous le seuil depuis... ») de **tous** les canaux déjà suivis, pas seulement
  celui qu'on ajoute — dans le pire cas (canaux démarrés en cascade rapprochée), une coupure
  pourrait ne jamais se déclencher.
  **Correctif validé en direct avant d'être codé** (comme pour la découverte initiale de la limite
  des 3 scripts) : `Script.Eval` permet d'exécuter du JS dans le contexte d'un script **déjà en
  cours d'exécution**, en lisant et modifiant ses variables de haut niveau sans jamais le
  redémarrer — testé avec un compteur qui continue d'incrémenter sans interruption après une
  mutation par `Eval` sur une autre variable, puis avec un tableau d'objets (`.push()` + relecture
  via `JSON.stringify`, persistant entre appels séparés). Piste de repli envisagée puis écartée :
  reécrire discrètement le texte enregistré du script (`Script.PutCode`) sans le redémarrer, pour
  qu'un vrai redémarrage matériel retrouve au moins le dernier état connu — testé et **refusé net**
  par l'appareil (`-103 "The script is running!"` : `PutCode` exige `Stop` au préalable, donc
  impossible sans perdre le bénéfice recherché).
  Architecture retenue : `generateSupervisor()` ne sert plus qu'au **tout premier déploiement**
  (aucun script existant, ou existant mais arrêté) ; ajouter/retirer un canal sur un script déjà en
  cours passe exclusivement par `Script.Eval` (nouvelles fonctions `evalUpsertChannel()`/
  `evalRemoveChannel()`, reconstruisent `CFG`/`STATE` via un tableau tampon + `for`/`push`, jamais
  `.splice()` — même prudence que pour `Array.map()`, non testé). Limite acceptée en connaissance
  de cause : un canal ajouté uniquement via `Eval` ne survit pas à un vrai redémarrage matériel
  (coupure secteur, mise à jour firmware) survenant pendant que son minuteur tourne — cas rare,
  sans danger, juste la protection à relancer manuellement si ça arrive.
  `cutoffScriptFired` basé sur `Switch.GetStatus.source == "loopback"` (piste validée en direct la
  veille sur le script bouton) au lieu de l'ancienne détection « le script a disparu », qui ne se
  généralisait pas à un script partagé. Le générateur `generate()`/`parseThreshold()`/
  `uniquePlanningScriptName()` d'origine, utilisé uniquement par la coupure de planning (jamais
  partagé par conception, un script par occurrence, pas concerné par la limite de 3), reste
  **entièrement inchangé**. Migration simplifiée par rapport au Lot A : ce script est transitoire
  par nature (actif seulement pendant qu'un minuteur tourne, se désactive seul), donc pas d'état à
  préserver — les éventuels anciens scripts `hestia_charge_<canal>` sont simplement supprimés à la
  prochaine lecture. Signatures publiques de `DeviceRepository`
  (`startChargeTimer`/`startUnlimitedChargeTimer`/`cutoffScriptFired`/`cancelTimer`) inchangées,
  aucune UI à toucher.
  Lot C (présence, notif de fin) pas encore commencé.

## Fonctionnalités futures

- **Audit des fonctions RPC de la prise non gérées** par Hestia (mesure d'énergie détaillée,
  métriques cumulées, etc.).
- **Lecture à distance de la puissance tirée, hors réseau local** (2026-07-29) : David voudrait
  voir la conso instantanée d'un appareil branché même hors Wi-Fi domestique, à l'image d'une
  appli de suivi de charge de scooter — mais la prise ne connaît que le côté électrique (watts),
  jamais un pourcentage de batterie ni un temps restant (ça, c'est propre au BMS de l'appareil
  branché, la prise ne le voit pas). Passerait par le **cloud Shelly** (compte Allterco), en
  lecture seule, opt-in et bien expliqué — jamais imposé. Chantier réel : API cloud distincte de
  l'API locale, stockage d'identifiants (nouvelle catégorie de donnée sensible). Mis de côté
  volontairement pour ne pas alourdir l'app pour un usage occasionnel. Deux idées écartées pour
  le même besoin : notifications ntfy (résout la notification à distance, pas la lecture de
  puissance) ; VPN personnel type Tailscale (fonctionnerait déjà, hors périmètre d'Hestia).
- **Fonctions liées au firmware Shelly 2.0.0** (compteurs d'usage natifs du Switch, paramètre `tag`
  sur les commandes, `Script.addRpcHandler`) : évoquées le 2026-07-29, revues le 2026-08-14 — plus
  « écartées » mais pas encore planifiées. Principe retenu pour quand on s'y attaque : Hestia part
  du principe que l'utilisateur met ses appareils à jour, **pas de rétrocompatibilité artificielle
  à maintenir**. Chaque fonction propre à 2.0.0 vérifie juste la version installée (déjà lue en
  local, `Shelly.GetDeviceInfo`, aucun appel réseau) contre le minimum qu'elle exige ; si en
  dessous, message clair invitant à mettre à jour via le bouton « Vérifier une mise à jour » déjà
  existant (qui, lui, reste seul à contacter les serveurs Shelly, toujours manuel). Pas de scan de
  capacités RPC au cas par cas (jugé inutilement lourd) — juste ce test de version.
  **Revue complète du changelog officiel le 2026-08-15**, points supplémentaires identifiés :
  alarmes seuil natives sur EM/EM1/PM1 (recoupe la coupure sur seuil actuelle, gérée par script
  maison — à voir si ça la simplifierait, matériel EM/PM différent d'un Switch classique donc pas
  garanti applicable) ; réglages LED du PowerStrip **Gen4** en interface web native (à vérifier le
  jour où un Strip Gen4 est testé — schéma RPC potentiellement différent de ce qu'on a validé sur
  Gen3 pour la LED d'état) ; objet `alt` dans `CheckForUpdate` (pourrait enrichir l'écran firmware
  existant). **Point de vigilance repéré, pas un bug vécu** : la 2.0.0 ajoute un redirect HTTP→HTTPS
  automatique quand la sécurité renforcée (`enhanced_security`) est activée côté appareil — si un
  utilisateur l'active un jour, les appels HTTP en clair d'Hestia (`cleartextTrafficPermitted`)
  pourraient casser. Aucun de nos appareils de test ne l'a activée pour l'instant.
- **Cloud Shelly activable par appareil** (2026-08-17) : un interrupteur, par appareil physique
  (pas global à l'app), pour activer le cloud natif de Shelly (`cloud.enable`, déjà vu désactivé
  dans nos dumps `Shelly.GetConfig` cette session). Permettrait de garder la plupart des appareils
  strictement locaux tout en ouvrant ceux qui en profiteraient (détecteurs de fumée notamment,
  utiles à surveiller même hors du réseau domestique) — opt-in, par appareil, cohérent avec le
  principe déjà posé pour ntfy (« toute fonctionnalité qui fait sortir des données du réseau
  local doit être opt-in et clairement expliquée »). Techniquement simple (un seul appel RPC
  `Cloud.SetConfig`), l'essentiel de l'effort serait le texte d'explication : bien préciser que
  c'est le cloud de **Shelly**, pas un service Hestia, qu'Hestia n'ajoute aucune fonction de
  surveillance à distance elle-même (ça resterait dans l'app/compte Shelly), et qu'Hestia ne crée
  ni ne gère jamais de compte (ligne rouge du projet, inchangée). **À vérifier avant tout code** :
  est-ce que `cloud.enable=true` suffit à connecter l'appareil, ou faut-il un appairage préalable
  via l'appli Shelly (compte créé par l'utilisateur lui-même, jamais par Hestia) — déterminerait
  si Hestia ne fait qu'« ouvrir la porte » ou doit aussi expliquer une étape supplémentaire hors
  de l'app.
- **Trouver l'IP du hotspot directement depuis Hestia** (2026-08-14) : un bouton « Trouver l'IP »
  par champ IP, qui interroge l'admin de la prise (`192.168.33.1`) pendant qu'elle est encore en
  mode point d'accès, pour lire l'IP qu'elle vient d'obtenir sur le réseau cible. Ne fonctionne que
  dans cette fenêtre précise (téléphone connecté au Wi-Fi propre de la prise, juste après lui avoir
  donné le Wi-Fi cible) — ne résout pas le cas où la plage entière du hotspot change à chaque
  redémarrage (vécu en vacances). Utile comme petit confort, pas comme solution générale. Note :
  une bonne partie de ce besoin sera couverte par le provisioning direct (voir lot en cours
  ci-dessus, « Ajout d'un appareil neuf »), qui lit cette même IP automatiquement au passage.
- **Lien Liberapay (ou PayPal) dans À propos** (2026-08-14) : un lien de plus à côté de la licence
  GPL, aucun impact vie privée (lien ouvert à la demande de l'utilisateur). Liberapay plutôt que
  PayPal si un seul à choisir — plus dans l'esprit du projet.
- **Bluetooth en repli du Wi-Fi** (2026-08-14) : la prise expose aussi ses méthodes RPC en Bluetooth
  Low Energy (trame différente : longueur 4 octets + JSON, découpée par MTU). Le Wi-Fi resterait
  toujours le chemin par défaut ; en cas d'échec Wi-Fi, message clair « Appareil injoignable en
  Wi-Fi » avec action explicite « Essayer en Bluetooth » — jamais de bascule automatique invisible
  (le BLE suppose d'être à portée physique). Chantier réel non trivial : nouveau transport derrière
  l'abstraction driver existante, appairage BLE géré par Android, connexion avec état (contrairement
  au Wi-Fi), protocole de trame à écrire et tester, permissions BLE qui varient selon la version
  Android. **Pas encore testé de bout en bout** — à valider à la main (nRF Connect) avant d'estimer
  sérieusement l'effort. Priorité modérée : le bouton physique couvre déjà l'essentiel du cas
  « à portée mais pas de Wi-Fi » pour une prise.
- **Tags QR code** (2026-08-14) : coller un QR code physique sur un appareil (ex. un Mac) pour
  lancer directement sa programmation au scan, via un deep link Android (`hestia://tag/<uuid>`).
  Le tag encode un identifiant opaque, jamais un nom — robuste au renommage. Modèle retenu : des
  tags pré-générables en lot (utile pour une commande d'impression groupée), associables/
  réassociables/détachables à une programmation à tout moment sans réimprimer, effaçables
  définitivement en cas de perte. Génération interne avec logo au centre (bonus, un QR externe doit
  aussi fonctionner). Chantier de taille comparable à ntfy en son temps — plusieurs lots à prévoir
  (deep link, cycle de vie des tags, écran de gestion, export PDF pour impression via l'API PDF
  native Android, pas de nouvelle dépendance nécessaire).
- **Déplacer « Mise à jour » et « Redémarrer » vers la page Modifier d'un appareil** (2026-08-18) :
  aujourd'hui dupliqués à l'identique sur l'écran Détail de chaque canal d'un même bloc multi-
  canaux (un seul appareil physique, donc un seul firmware, un seul redémarrage) — recoupe le
  chantier « par appareil physique, pas par canal » déjà identifié en creusant le script
  superviseur (voir § En cours de traitement). À rapprocher de la LED d'état, déjà gérée par
  appareil physique dans `AddEditDeviceScreen` (page « Modifier ») plutôt que par canal : même
  logique à appliquer à la mise à jour firmware et au redémarrage.
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
- **Notifications** : WorkManager (~15 min, délai assumé et annoncé dans l'UI), opt-in, désactivé
  par défaut, `POST_NOTIFICATIONS` demandée à l'activation seulement. Notifie les **bornes** de
  programmation (une seule notification par borne), la fin d'un minuteur et la coupure sur seuil ;
  jamais une action manuelle. **Complété le 2026-07-29** par une option **ntfy** (notifications
  instantanées, désactivée par défaut) — voir entrée dédiée plus bas ; la tension "100 % local"
  notée ici initialement est retranchée, cf. § Notifications instantanées (ntfy).
- **Écran de configuration de présence** : supprimé (la présence se gère depuis l'écran de
  détail, comme le planning), ce qui rend caduque l'ancienne demande de confirmation
  « modifications non enregistrées ». La route de navigation `presence` associée, restée morte
  dans `Destinations.kt` après cette suppression, a été retirée au lot Nettoyage (2026-07-27).
- **Vérification manuelle du firmware** : section « Mise à jour du firmware » sur l'écran de
  détail, bouton « Vérifier » qui interroge `Shelly.CheckForUpdate` — jamais en tâche de fond.
  Bêta signalée mais jamais installable. Tranché : pas de notification de mise à jour disponible
  (contrairement à ce qu'envisageait l'ancienne entrée de ce backlog) — la vérification reste
  ponctuelle, à la main de l'utilisateur. (N'est plus la seule action à sortir du réseau local
  depuis l'ajout de ntfy, opt-in — voir plus bas.)
- **Coupure sur seuil, ergonomie** : case à cocher remplacée par un interrupteur (désactivé par
  défaut, même style que Notifications), et saisie libre du seuil remplacée par une roulette sur
  des valeurs prédéfinies (5, 10, 20, 30, 40, 50 W, défaut 10) via le nouveau `ValueWheelPicker`,
  qui généralise `TimeWheelPicker` à une liste de valeurs arbitraire.
- **Redémarrage manuel de l'appareil** (dépannage) : bouton sous la section firmware
  (`Shelly.Reboot`), gated sur `DriverType.SHELLY_GEN2` plutôt qu'affiché sans condition — n'a
  pas d'équivalent générique si une autre marque est gérée un jour. Jamais bloqué par un
  minuteur en cours (peut justement servir à débloquer une prise plantée). Confirmation
  obligatoire avant déclenchement.
- **Planning « Unique »** (occurrence unique datée, plutôt que récurrente) : cron avec
  jour-du-mois/mois renseignés (au lieu de `*`) au lieu d'un bricolage à part. Nettoyage
  automatique du planning expiré à la prochaine lecture (pas de tâche de fond). Chips
  Aujourd'hui/jour précis en sélection unique, remplacent « Tous les jours » en mode Unique.
- **Coupure sur seuil pour les plannings**, Unique **et récurrent** : script dédié par planning
  (jamais partagé, ni entre eux ni avec le minuteur — un nom de script fixe aurait fait échouer
  `Script.Create` en cas de collision), démarré/arrêté par le même programme cron que
  l'allumage/extinction (`calls` de `Schedule.Create`, jusqu'à 5 appels, validé en direct). Pour
  le récurrent : validé qu'un script relancé après un `Script.Stop` réexécute proprement depuis
  le début (`let` réinitialisés), donc se réarme correctement à chaque occurrence sans recréer
  quoi que ce soit.
- **Deux réglages « Perso » nommables** (au lieu d'un seul, anonyme) : chacun avec un nom libre
  (20 caractères max), sa propre durée et coupure optionnelle. Migration Room additive (v7→v8).
- **Notifications instantanées (ntfy)** — 2026-07-29 : option opt-in, désactivée par défaut, qui
  fait notifier **l'appareil lui-même** (`HTTP.Request` vers `ntfy.sh`, jamais `HTTP.POST` qui ne
  permet pas d'en-têtes personnalisés donc pas de titre) au lieu du téléphone via WorkManager —
  supprime le délai de 0 à 15 min. Sujet ntfy stocké chiffré (Android Keystore,
  `androidx.security:security-crypto`). Un script dédié, nouveau, est déployé uniquement pour
  notifier la fin naturelle d'un minuteur Manuel/Perso **sans** coupure (seul cas sans aucun
  script associé jusque-là) ; nettoyé systématiquement avant toute extinction manuelle pour ne
  jamais notifier une action utilisateur (bug trouvé et corrigé avant la mise en usage réel).
  Bascule automatiquement l'ancien système (WorkManager) en repli si désactivé. **Rattrapage** :
  un appareil injoignable au moment du bascule est resynchronisé silencieusement dès que le
  Tableau le recontacte (déjà interrogé en boucle toutes les 5 s), via une génération comparée
  en préférences — pas de tâche de fond dédiée. Revu et corrigé les textes vie privée (À propos,
  fiche F-Droid, README, `CLAUDE.md`) qui promettaient encore « rien ne quitte jamais le réseau
  local » pour refléter cette exception explicite et opt-in.
- **Barre de statut système illisible en thème clair** (icônes blanches sur fond blanc) :
  `enableEdgeToEdge()` ne fixait la couleur des icônes qu'au démarrage selon le thème système,
  sans suivre le réglage propre à l'app (Réglages → Apparence) ni ses changements en direct.
  Recalée à chaque changement de thème effectif via `WindowInsetsControllerCompat`.
- **Minuteur déclenché par le bouton physique** : appui sur le bouton = arme un minuteur (durée +
  coupure sur seuil optionnelle), configurable comme un réglage Perso mais sans passer par
  l'appli. Placé à côté de la section Firmware (« c'est du matos »). Appui long/double impossible
  sur ce matériel (pas de composant Input adressable en mode bouton natif, vérifié en direct).
  Sans réglage, le bouton retrouve le comportement natif de la prise (simple on/off).
- **Bascule automatique entre deux adresses IP par appareil** (2026-08-14) : chaque appareil peut
  avoir un 2ᵉ emplacement IP nommé (ex. « Domicile » / « Vacances »), Hestia retente
  automatiquement l'autre en cas d'échec réseau sur le premier et mémorise lequel a répondu en
  dernier pour l'essayer en priorité au prochain appel. Toujours au moins une IP configurée
  (poubelle masquée sur le dernier emplacement restant). L'IP réellement utilisée est maintenant
  affichée dynamiquement (Réglages et fiche Détail), plus jamais figée sur le 1ᵉʳ emplacement.
- **Corrections diverses signalées à l'usage réel** (2026-08-14, remontées après un séjour avec la
  prise sur un hotspot mobile) : journal de diagnostic réduit à 250 lignes (2000 auparavant, plus
  de sens à ce stade) ; texte du bouton physique simplifié, explique désormais le comportement par
  défaut sans réglage ; renommer un appareil redéploie automatiquement les scripts qui contiennent
  son nom (bouton, présence, planning) — le nom était écrit en dur au déploiement, jamais relu par
  la prise ensuite ; notification de fin manquante pour un minuteur Perso/Manuel à seuil qui va au
  bout de sa durée sans jamais couper (le seul cas resté silencieux) ; bouton « Partager »
  (partage système Android, texte + lien F-Droid) ajouté dans À propos, à côté du QR code.
- **Publication F-Droid 1.1.0** : tag Git pointant le commit de version, fiche de référence
  (`fdroid/kapoue.hestia.yml`) mise à jour avec le nouveau build et les catégories `Connectivity` +
  `Remote Controller` (seconde catégorie demandée en relecture). Accès en écriture au fork GitLab
  de `fdroiddata` vérifié (`git push --dry-run`), MR à pousser réellement à la prochaine
  publication groupée avec les lots en cours.
- **Pause de planning** (2026-08-14) : bouton pause à côté de la poubelle sur un planning actif
  (avertit si en cours, comme la suppression). Supprime réellement les programmes de l'appareil,
  mémorise horaires/jours/seuil en local (nouvelle table `paused_plannings`) pour recréer à
  l'identique à la réactivation — avec les mêmes contrôles de conflit qu'une création normale.
  Un planning en pause peut aussi être oublié définitivement sans jamais le réactiver.
- **Minuteur sans limite de durée** (2026-08-14, remplace l'idée initiale de « Détection de fin de
  charge » en réutilisant l'existant plutôt qu'un nouveau système) : un switch dans le sélecteur de
  durée retire la limite de temps, coupure sur seuil seule (alors obligatoire), avec une grâce fixe
  de 15 min avant toute surveillance — absente sinon, pour ne pas changer le comportement déjà en
  place. S'applique aux 2 réglages Perso, au minuteur Manuel et au bouton physique. Limite connue :
  la tuile du Tableau n'affiche ni compte à rebours ni seuil pendant une charge sans limite (pas de
  minuteur natif auquel accrocher ces indicateurs).
- **Regroupement des appareils multi-canaux** (2026-08-14) : chantier le plus large du lot, en
  plusieurs passes après retours d'usage réel (Strip 4).
  - **Nom d'appareil stable** (`Device.deviceName`, migration v11→v12) : enregistré une fois à
    l'ajout, identique sur tous les canaux d'un même appareil, jamais affecté par le renommage
    d'un canal individuel (ex. « Frigo »). Corrige un vrai défaut trouvé en cours de route : deviner
    ce nom en inspectant les canaux se cassait dès qu'on personnalisait le premier d'entre eux.
  - **Tableau** : canaux d'un même appareil physique (même IP) toujours groupés côte à côte à
    l'affichage (jamais mélangés avec un autre appareil), avec un en-tête (picto + nom d'appareil)
    au-dessus. Numéros `01/02…` sur les tuiles retirés (n'apportaient plus rien une fois l'ordre
    corrigé). Nouveau séparateur `·` pour les noms de canaux par défaut (« Nom · 1 »), cohérent avec
    celui déjà utilisé dans Réglages ; les noms déjà enregistrés avec l'ancien format (espace simple)
    sont corrigés automatiquement au lancement, sans action de l'utilisateur.
  - **Réglages** : même ordre groupé qu'au Tableau (calcul partagé, `DeviceRepository.
    groupedForDisplay`), un bloc multi-canaux devient un en-tête (nom d'appareil + IP partagée +
    monter/descendre une seule fois pour tout le groupe + menu Modifier/Ouvrir/Supprimer, même
    présentation qu'une prise seule) suivi d'une ligne par canal (nom propre, crayon pour le
    renommer, poubelle pour le retirer seul). Modifier l'IP ou le type depuis l'en-tête s'applique
    à tous les canaux d'un coup — évite qu'un seul canal se retrouve avec une IP différente des
    autres (ils partagent la même prise physique). Point non tranché, à surveiller à l'usage : le
    type (Prise/Lampe/Capteur) est uniformisé sur tout le bloc par ce même écran, pas réglable par
    canal.
  - **Deux bugs multi-canaux trouvés en direct sur Strip 4, corrigés (2026-08-15)** :
    - **Collision de nom de script entre canaux** : le moteur de scripts Shelly est partagé par
      tout l'appareil physique (pas un par canal) — les quatre générateurs (`ButtonTimer`,
      `Charge`, `Presence`, `TimerNotify`) utilisaient un nom de script fixe, retrouvé/écrasé
      d'un canal à l'autre du même appareil au lieu d'être propre à chacun. Diagnostiqué via
      `Script.List`/`Script.GetCode` en direct (deux scripts distincts ciblant le même canal).
      Corrigé : nom de script désormais suffixé par le canal (`hestia_xxx_<switchId>`) partout,
      y compris dans `DeviceRepository` (tous les points d'appel).
    - **Bouton physique du bloc prise inopérant** : `Switch.GetStatus.source` vaut `"button"` sur
      Plug M Gen3 mais `"short_push"` sur Strip 4 — le script de minuteur bouton ne s'armait
      donc jamais sur ce second modèle (programmation posée, mais ni durée ni seuil n'agissaient).
      Corrigé par un helper `isButtonSource(src)` acceptant les deux valeurs confirmées en direct,
      plus `long_push`/`double_push`/`triple_push` par prudence (non vérifiées sur du matériel réel).
  - Spinner de rafraîchissement automatique du Tableau (toutes les 5 s) : ne doit plus jamais
    apparaître hors tirage manuel, quelle que soit la durée du cycle — l'indicateur temporisé
    précédent (déclenché après 600 ms si un appareil traînait) est retiré.
- **Refonte du Tableau, lot 1/5 — permission réseau en message global** (2026-08-17) : retiré
  l'état « Permission requise » par tuile (`TileStatus.PermissionRequired` supprimé du modèle
  partagé, tuiles concernées basculées en `Offline`), remplacé par un bandeau unique en tête du
  Tableau quand la permission manque, avec la même action que la section Réglages (ouvre
  directement les réglages système, ne redemande jamais la permission depuis l'app). Écran Détail
  aligné sur le même principe (repli sur l'état « Hors ligne », pas de message dédié — il n'est
  accessible qu'après être passé par le Tableau, où le bandeau est déjà visible). Prélude à la
  refonte visuelle complète des tuiles (lots 2 à 5 : anatomie de la tuile solo, bloc multi-canaux
  en ligne avec modale, bouton lié au minuteur physique, mise en avant du programme en cours).
- **Refonte du Tableau, lot 2/5 — anatomie de la tuile solo** (2026-08-17) : cercle inspiré de la
  vraie prise (deux trous), fond de tuile teinté par état (clair + sombre, nouvelles valeurs
  `StateColorSet.*Bg`), bouton rond neutre séparé de l'état, consommation en bas à gauche, retrait
  du picto de type d'appareil sur la tuile, retrait du bouton « Réessayer » (le rafraîchissement
  auto 5 s + le tirage manuel suffisent). Deux couleurs distinctes et volontairement indépendantes :
  l'anneau du cercle reflète le fait physique (courant ou non), le fond/texte reflète le régime
  (actif seul / piloté par un programme / éteint / indisponible) — ex. une présence en pause reste
  « Planifié » en orange même si l'anneau est gris à cet instant. Libellés simplifiés : « Planifié »
  unifie minuteur/présence/planning sur la tuile (l'écran Détail garde ses libellés précis, via
  `StatusBadge`, non touché) ; « Éteint » remplace « Repos » ; « Indisponible » remplace
  « Hors ligne » partout. Point laissé en suspens : le petit picto de type au-dessus de la tuile
  (en-tête de groupe) n'a pas été retiré, jamais explicitement inclus dans les échanges sur la
  tuile elle-même — à trancher.
- **Refonte du Tableau, lot 3/5 — bloc multi-canaux en ligne** (2026-08-17) : la grille 2×2 par
  canal est remplacée par une seule ligne de petits cercles (`DeviceStripRow`), façon vraie
  multiprise. Chaque cercle reprend l'anneau/les trous du cercle solo, avec nom (10 caractères
  max, tronqué sans « … ») et état sur deux lignes en dessous — jamais de couleur seule, même à
  cette échelle. Pas de bouton ON/OFF direct sur le Tableau pour un canal de bloc (contrairement à
  une prise seule) : le tap ouvre une modale (`ChannelQuickSheet`, bottom sheet) avec conso,
  interrupteur, état complet, et un lien « Voir le détail » vers l'écran complet (plannings,
  présence, seuils — hors du périmètre de la modale).
- **Refonte du Tableau, lot 4/5 — bouton app relié au minuteur physique** (2026-08-17) : allumer
  depuis le bouton rond de l'app (tuile solo ou modale du bloc) relit d'abord le minuteur configuré
  pour le bouton physique de l'appareil et reproduit exactement son comportement — durée seule,
  durée + seuil, ou seuil sans limite de durée — via les fonctions déjà existantes (`startTimer`/
  `startChargeTimer`/`startUnlimitedChargeTimer`, réutilisées telles quelles, aucune nouvelle
  fonction repository). Sans minuteur bouton configuré, allumage classique inchangé. Éteindre reste
  toujours un `Switch.Set` direct, jamais concerné, quel que soit ce qui a déclenché l'allumage.
  Limite connue et assumée : le script du minuteur bouton distingue une annulation volontaire d'une
  fin naturelle via le champ `source`, détecté par le matériel — un allumage déclenché depuis l'app
  ne peut pas se faire passer pour un appui physique ; seule la formulation d'une notification de
  fin pourrait en être affectée, jamais l'action elle-même (coupure toujours effective).
- **Correctif couleur de fond, retour de test réel** (2026-08-17) : le fond de la tuile (et des
  petits cercles du bloc) suit désormais **toujours** le fait physique (vert si le courant passe,
  gris sinon), même quand un programme est en cours — plus de fond orange pour « Planifié »,
  jugé source de confusion en usage réel sur le Strip 4. La nuance « un programme pilote la
  prise » reste lisible, mais uniquement dans le texte (« Planifié » en orange), jamais dans le
  fond. `StateColorSet.timedBg` retiré (devenu inutilisé).
- **Refonte du Tableau, lots 1 à 4/5** (2026-08-17) : permission en message global, tuile solo
  façon vraie prise, bloc multi-canaux en ligne + modale, bouton app lié au minuteur physique —
  livrés et validés en test réel sur Pixel/Sony, Plug M et Strip 4. **Le lot 5/5 (mise en avant du
  programme en cours) est codé mais pas encore testé** — voir § En cours de traitement, gardé en
  l'état intentionnellement pour l'instant.
- **LED d'état par appareil** (2026-08-15) : interrupteur dans la boîte « Modifier » (solo
  et bloc), un seul réglage par appareil physique. **ON** = 100 % le jour, réduite à 30 % de 22h à
  8h ; **OFF** = éteinte en permanence. Entièrement natif au firmware (`night_mode` du composant
  `plugs_ui`/`powerstrip_ui`), réutilisé pour les deux états (fenêtre 22h-8h à 30 % pour ON, fenêtre
  toute la journée à 0 % pour OFF) — aucun script, aucune configuration stockée par Hestia. Deux
  pièges confirmés en direct sur le Strip 4 et le Plug M avant d'écrire le code : le nom du
  composant diffère selon le modèle (solo vs bloc), **et sa casse aussi** (`plugs_ui` en
  minuscules, `POWERSTRIP_UI` en majuscules) — `DeviceRepository.getLedState`/`setLedState`
  essaient les combinaisons plausibles plutôt que d'en figer une.
- **Build F-Droid cassé par R8, trouvé et corrigé** (2026-08-15) : le build release de la 2.0.0
  a échoué sur l'infrastructure F-Droid (CI `checkupdates-bot-fdroiddata`) — R8 refusait de
  continuer sur 4 classes manquantes de `com.google.errorprone.annotations`, référencées par
  Google Tink (dépendance interne de `androidx.security:security-crypto`, utilisée pour chiffrer
  le sujet ntfy). Jamais vu en local car seul `assembleDebug` avait été testé — R8 ne tourne que
  sur la variante `release`. Corrigé par une règle `-dontwarn` dans `proguard-rules.pro` (annotations
  de compilation uniquement, jamais utilisées à l'exécution), validé ensuite par un vrai
  `assembleRelease` local réussi. **Conséquence** : les tags 2.0.0 et 2.1.0 resteront cassés sur
  F-Droid (commit figé, non corrigeable rétroactivement) — la 2.2.0 est la première version à
  intégrer le correctif. Signalée par un mainteneur F-Droid via une issue Codeberg le jour même,
  réponse technique postée avec lien vers le correctif.
- **Canal de communication Mastodon + Telegram** (2026-08-15) : finalement un **compte
  dédié par application** plutôt qu'un compte unique pour tout le portefeuille (décision prise en
  cours de route) — `@hestia_app@mastodon.social` et canal public Telegram `Hestia_app` (posté via
  le bot `@hestia_app_bot`), même modèle prévu pour les autres applis (Agora, MainTask, Kartapuss,
  Ignis). Création des comptes forcément manuelle (David) — Claude Code ne crée jamais de compte.
  Section « Contact » ajoutée à l'À propos (FR/EN) avec liens vers les deux. Publication
  automatisée en place (jetons API dans `~/.hestia-social.env`, hors dépôt), toujours avec
  confirmation explicite avant chaque envoi réel — jamais silencieux. Changelogs F-Droid par
  version rédigés en parallèle (`fastlane/metadata/android/*/changelogs/<versionCode>.txt`).
