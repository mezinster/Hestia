# Backlog — Hestia

Points relevés en cours de route, à traiter dans un lot ultérieur (pas des bugs bloquants).
Dernière mise à jour : 2026-08-14 (regroupement multi-canaux + nom d'appareil stable, pause de
planning, minuteur sans limite de durée, F-Droid 2.0.0).

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
- **Fonctions liées au firmware 2.0.0** (compteurs d'usage natifs du Switch, paramètre `tag` sur
  les commandes, `Script.addRpcHandler`) : évoquées le 2026-07-29, revues le 2026-08-14 — plus
  « écartées » mais pas encore planifiées. Principe retenu pour quand on s'y attaque : Hestia part
  du principe que l'utilisateur met ses appareils à jour, **pas de rétrocompatibilité artificielle
  à maintenir**. Chaque fonction propre à 2.0.0 vérifie juste la version installée (déjà lue en
  local, `Shelly.GetDeviceInfo`, aucun appel réseau) contre le minimum qu'elle exige ; si en
  dessous, message clair invitant à mettre à jour via le bouton « Vérifier une mise à jour » déjà
  existant (qui, lui, reste seul à contacter les serveurs Shelly, toujours manuel). Pas de scan de
  capacités RPC au cas par cas (jugé inutilement lourd) — juste ce test de version.
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
- **LED d'état par appareil (fait, 2026-08-15)** : interrupteur dans la boîte « Modifier » (solo
  et bloc), un seul réglage par appareil physique. **ON** = 100 % le jour, réduite à 30 % de 22h à
  8h ; **OFF** = éteinte en permanence. Entièrement natif au firmware (`night_mode` du composant
  `plugs_ui`/`powerstrip_ui`), réutilisé pour les deux états (fenêtre 22h-8h à 30 % pour ON, fenêtre
  toute la journée à 0 % pour OFF) — aucun script, aucune configuration stockée par Hestia. Deux
  pièges confirmés en direct sur le Strip 4 et le Plug M avant d'écrire le code : le nom du
  composant diffère selon le modèle (solo vs bloc), **et sa casse aussi** (`plugs_ui` en
  minuscules, `POWERSTRIP_UI` en majuscules) — `DeviceRepository.getLedState`/`setLedState`
  essaient les combinaisons plausibles plutôt que d'en figer une.
- **Build F-Droid cassé par R8 (trouvé et corrigé, 2026-08-15)** : le build release de la 2.0.0
  a échoué sur l'infrastructure F-Droid (CI `checkupdates-bot-fdroiddata`) — R8 refusait de
  continuer sur 4 classes manquantes de `com.google.errorprone.annotations`, référencées par
  Google Tink (dépendance interne de `androidx.security:security-crypto`, utilisée pour chiffrer
  le sujet ntfy). Jamais vu en local car seul `assembleDebug` avait été testé — R8 ne tourne que
  sur la variante `release`. Corrigé par une règle `-dontwarn` dans `proguard-rules.pro` (annotations
  de compilation uniquement, jamais utilisées à l'exécution), validé ensuite par un vrai
  `assembleRelease` local réussi. **Conséquence** : les tags 2.0.0 et 2.1.0 resteront cassés sur
  F-Droid (commit figé, non corrigeable rétroactivement) — la 2.2.0 est la première version à
  intégrer le correctif.
- **Canal de communication Mastodon + Telegram (fait, 2026-08-15)** : finalement un **compte
  dédié par application** plutôt qu'un compte unique pour tout le portefeuille (décision prise en
  cours de route) — `@hestia_app@mastodon.social` et canal public Telegram `Hestia_app` (posté via
  le bot `@hestia_app_bot`), même modèle prévu pour les autres applis (Agora, MainTask, Kartapuss,
  Ignis). Création des comptes forcément manuelle (David) — Claude Code ne crée jamais de compte.
  Section « Contact » ajoutée à l'À propos (FR/EN) avec liens vers les deux. Publication
  automatisée en place (jetons API dans `~/.hestia-social.env`, hors dépôt), toujours avec
  confirmation explicite avant chaque envoi réel — jamais silencieux. Changelogs F-Droid par
  version rédigés en parallèle (`fastlane/metadata/android/*/changelogs/<versionCode>.txt`).

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
