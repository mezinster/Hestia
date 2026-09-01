# Détecteur de fumée Shelly — plan et faits confirmés

Document de référence autonome pour ce chantier, pour ne pas avoir à fouiller BACKLOG.md à
chaque fois. Mis à jour au fil de l'eau ; BACKLOG.md garde l'historique complet et le détail des
échanges, ce fichier n'en garde que l'essentiel exploitable.

Matériel : deux Shelly Plus/Gen3 Smoke (`SNSN-0031Z`), tous deux fonctionnels après
réinitialisation d'usine. IP de test : `192.168.1.94` (mac `3076F538EEF4`) et `192.168.1.95`
(mac `3076F522A208`), réseau `Bbox-A1581AD9`.

## Plan par lots

Chaque lot produit quelque chose de réellement testable, pas juste du code invisible.

- **Lot 1 — Ajouter le détecteur. ✅ Codé le 2026-08-31, pas encore testé sur l'appareil réel.**
  Nouveau `DeviceType` dédié « Détecteur de fumée » (pas « Capteur » générique, réservé à
  d'éventuels autres capteurs futurs). Écran Nouvel appareil : pas de test de connexion (confirmé
  inutile, un scan réseau classique ne le trouve jamais vu qu'il dort la majeure partie du temps),
  bouton direct « Ajouter ». Insister sur l'activation ntfy + Cloud Shelly sur cet écran.
  → *Testable : ajouter le détecteur dans Hestia, le voir dans la liste.*

- **Lot 2 — Tuile Tableau avec données réelles. ✅ Codé le 2026-08-31, pas encore testé sur
  l'appareil réel.** Lecture `Smoke.GetStatus`/`DevicePower.GetStatus`/`Temperature.GetStatus` en
  un seul appel `Shelly.GetStatus` local si joignable, repli Cloud Control API sinon (même clé de
  compte que les prises, `DeviceRepository.getSensorStatus(es)`, calqué sur `getStatus(es)`).
  Pourcentage batterie (déjà calculé par le firmware, rien à calibrer côté Hestia), orange sous
  30 % (`StateColorSet.warningText`, nouveau — premier passage de couleur, à affiner). Heure du
  dernier contact via `_updated` (cloud) ou l'heure du téléphone (lecture locale réussie),
  affichée en relatif (« il y a 3 min ») via `DateUtils.getRelativeTimeSpanString` — localisé
  gratuitement, pas de nouvelles chaînes de pluriel à gérer. État « lecture impossible » distinct
  si `devicepower:0.errors` présent (peut être une vraie panne ou une config corrompue réparable
  par reset d'usine — jamais présenté comme définitif). Nouvelle tuile dédiée `SmokeDetectorTile`
  (pas de fait physique marche/arrêt, pas d'interrupteur, sur `DeviceTile.kt`), branchée dans
  `DashboardScreen.kt` selon `device.type`.
  → *Testable : la tuile affiche le vrai pourcentage batterie et l'heure du dernier contact.*

- **Lot 3 — Écran Détail. ✅ Codé le 2026-08-31, pas encore testé sur l'appareil réel — et
  réduit en route : pas de bouton Test.** Recherché avant de coder (comme prévu) : **l'API RPC
  Shelly n'expose aucune commande pour déclencher un test à distance** — seuls `Smoke.GetConfig`/
  `SetConfig`/`GetStatus`/`Mute` existent, aucun `Smoke.Test`. Le test ne se déclenche que
  physiquement, par appui sur le bouton de l'appareil (3 flashs + 3 bips). Un bouton « Test » dans
  Hestia aurait donc été un attrape-clic sans effet — abandonné, remplacé par un texte qui
  l'explique. Fait à la place : `Smoke.Mute` (grisé hors alarme réelle — le couper sans alarme
  n'a pas de sens, et ça n'agit que sur le son, jamais sur la détection elle-même), seuil batterie
  15 % affiché en texte fixe, température si disponible, état + dernier contact réutilisant
  `SensorStatus`/`formatLastContact` du Lot 2 (`DetailViewModel.fetch()` a maintenant un chemin
  entièrement séparé pour ce type, comme `DashboardViewModel`).
  → *Testable : ouvrir le détail, voir batterie/température/état, couper une vraie alarme.*

- **Lot 4 — Notifications ntfy.** Revu en profondeur en discutant (2026-09-01) : le plan initial
  « webhook natif → ntfy directement » ne fonctionne **pas** — voir § Faits techniques. Découpé en
  3 sous-lots :
  - **4a — script relais + déploiement opportuniste + webhooks sur les détecteurs. ✅ Codé le
    2026-09-01, pas encore testé sur l'appareil réel.** Nouveau `SmokeRelayScriptGenerator`
    (`hestia_smoke_relay`), déployé de façon opportuniste par `DeviceRepository.resyncSmokeRelay`
    sur jusqu'à 5 appareils scriptables ayant de la place (jamais un appareil désigné à l'avance —
    retour David : « je vois bien TOUS les appareils compatibles, tant pis pour les notifs en
    double »). `Webhook.Create` pour les 3 événements, ciblant `Script.Eval?id=..&code=notifySmoke(
    "event","MAC")` sur chaque relais (MAC ASCII, jamais le nom accentué — voir § Faits). Rattrapage
    automatique (`smokeWebhookCatchUpIfNeeded`, appelé par le Tableau dès qu'un détecteur répond en
    local) : pas besoin d'être dans l'app au moment exact du réveil de l'appareil pour que la
    couverture se mette en place. Déclencheurs : activation/sujet ntfy, ajout d'un détecteur.
    → *Testable : activer ntfy, ajouter/laisser un détecteur se réveiller une fois, puis déclencher
    un test (3 appuis) — vérifier que la notif arrive.*
  - **4b — éviction prioritaire du relais + picto dans Réglages.** Pas commencé. Un vrai réglage
    métier (présence, minuteur, coupure sur seuil) doit toujours pouvoir prendre la place du relais
    si l'appareil est saturé à 3/3 — jamais l'inverse.
    → *Testable : saturer un appareil à 3/3 relais compris, ajouter un vrai réglage dessus, vérifier
    que ça passe sans erreur.*
  - **4c — bandeau de couverture zéro.** Pas commencé. Bandeau permanent au-dessus de la barre de
    navigation (Accueil/Réglages/À propos) si aucun appareil ne peut relayer les alertes d'un
    détecteur présent, avec renvoi vers Réglages/ntfy. Message unique, sans distinguer « saturé »
    de « injoignable » (même remède : libérer de la place, ou utiliser l'appli officielle Shelly en
    attendant).
    → *Testable : saturer volontairement tous les appareils, voir le bandeau apparaître et pointer
    au bon endroit.*

- **Lot 5 — Coupure de prise en cas d'alarme.** Proposer la fonctionnalité ; si activée, cases à
  cocher sur les appareils connus d'Hestia (format « Nom noté dans Hestia — IP ») + champ de
  saisie libre pour une IP absente de la liste. Webhook natif du détecteur → `Switch.Set` direct
  sur l'IP visée (autonome, sans app ni script). Inciter à fixer l'IP (réservation DHCP côté box,
  méthode validée par David plutôt qu'IP statique sur l'appareil).
  → *Testable : déclencher un test, vérifier qu'une prise choisie se coupe vraiment.*

- **Lot 6 — Documentation.** Étendre le principe Cloud Shelly dans CLAUDE.md (« état des prises »
  → « état des prises et des capteurs »), toujours opt-in, jamais silencieux. À propos/Réglages.
  → Pas testable en soi, à caser n'importe quand.

## Faits techniques confirmés (2026-08-31, en conditions réelles)

**RPC local**
- `Smoke.GetStatus` → `{ id, alarm: bool, mute: bool }`
- `Smoke.Mute` → coupe l'alarme à distance
- `DevicePower.GetStatus` → `{ id, battery: { V, percent }, external: { present } }` — le firmware
  calcule déjà le pourcentage, aucune calibration tension→% à faire côté Hestia
- `Temperature.GetStatus` → **non confirmé sur ce matériel précis, à corriger.** Affirmé le
  2026-08-31 sur la seule foi d'un article communautaire (Shelly H&T et Shelly Smoke), jamais
  vérifié au curl à l'époque — erreur reconnue le 2026-09-01 après relecture des JSON déjà en
  main : **aucune des quatre réponses `Shelly.GetStatus` obtenues ce soir-là ou le lendemain
  (94 et 95, à deux moments différents) ne contient de clé `"temperature:0"`.** Ce modèle
  (`SNSN-0031Z`) ne semble donc pas exposer de capteur de température exploitable en RPC,
  contrairement à ce qui était supposé. Le code Hestia reste défensif (`temperatureC` nullable,
  n'affiche rien si absent — jamais de plantage), pas la peine de le retirer, mais ne pas
  compter dessus pour ce matériel. Leçon : la doc web générale sur une famille de produits ne
  remplace pas une vérification sur le SKU exact possédé.
- Webhooks natifs : `smoke.alarm`, `smoke.alarm_off`, `smoke.alarm_test` — l'appareil appelle une
  URL directement (ntfy, ou une autre prise Shelly), sans script ni app
- `Sys.GetStatus.wakeup_period` : confirmé à `86400` (24h) sur ce modèle — pas ~2h comme le cousin
  H&T. Réveil immédiat sur alarme malgré tout.

**Cloud Control API** (`https://<serveur>/v2/devices/api/get?auth_key=<clé>`, POST,
`{"ids":["<mac sans deux-points>"],"select":["status"]}`)
- **Même clé de compte que le repli Cloud des prises déjà dans Hestia** — confirmé par un appel
  réel, aucune clé séparée nécessaire.
- Champ `_updated` présent dans la réponse (horodatage de dernière remontée) — pas documenté
  explicitement par Shelly, confirmé en pratique. Utilisable tel quel pour « dernier contact ».
- L'URL vue en activant le Cloud depuis `192.168.33.1` (`shelly-api-eu.shelly.cloud:6022/jrpc`)
  est la connexion **propre à l'appareil** vers l'infra Shelly — **différente** du serveur HTTP
  que Hestia appelle (obtenu via l'authentification de compte).
- `devicepower:0.errors` (ex. `["read"]`) : peut apparaître même sans panne matérielle réelle —
  vécu en direct, une config corrompue a produit exactement cette erreur, résolue par une simple
  réinitialisation d'usine (5 appuis brefs sur le bouton). Ne jamais la présenter à l'utilisateur
  comme un défaut définitif sans lui suggérer un reset d'usine d'abord.

**Réinitialisation / réveil / test (bouton physique)** — doc officielle Shelly (guide utilisateur,
citée mot pour mot le 2026-09-01, après une confusion vécue en direct : 3 appuis brefs ne
déclenchent **pas** le test, seulement le mode configuration, LED verte clignotante + halo bleu)
- Appui bref (1x) : coupe une alarme en cours (mute)
- **3 appuis brefs : mode configuration 2 min** (réveil, sans rien effacer) — pas le test
- **Appui long, plus de 3 secondes : déclenche le test** (3 flashs rouges + 3 bips)
- 5 appuis brefs : réinitialisation d'usine (efface Wi-Fi + réglages), repasse en config 2 min
  ensuite

**LED d'état** (repère « C » sur l'appareil, rouge uniquement)
- 1 flash/53s **silencieux** = fonctionnement normal ; 1 flash/53s **avec un bip** = pile faible
  (même flash visuel, seul le son distingue) — ne jamais se fier au flash seul
- 2 flashs/53s sans bip = anomalie
- 3 flashs + 3 bips = test
- Flashs et bips continus = alarme réelle
- Vert clignotant = mode configuration (setup)

**Limite structurelle**
- Appareil à pile, dort la quasi-totalité du temps — le modèle de sondage 5s d'Hestia ne
  s'applique pas. RPC local en priorité si joignable, Cloud Shelly (opt-in) en secours pour
  l'état — pas de tâche de fond permanente ni de rattrapage rétroactif sans Cloud.

## Faits techniques confirmés (2026-09-01, en creusant le Lot 4)

**Le webhook natif Shelly ne peut pas appeler ntfy directement**
- `Webhook.Create` (doc officielle Shelly, Gen2/Gen3) : champs `cid`, `event`, `urls` (5 max,
  300 caractères chacune), `enable`, `name`, `condition`, `repeat_period`, `active_between`,
  `ssl_ca` — **aucun champ pour choisir une méthode HTTP, un corps ou des en-têtes**. Le webhook
  natif ne sait faire qu'une simple requête **GET** vers chaque URL.
- ntfy.sh exige **POST ou PUT** pour publier un message — vérifié en direct (curl réel + relecture
  du flux JSON du topic) : aucune variante de GET testée (`?message=&title=`, `?m=&t=`) ne publie
  quoi que ce soit, y compris quand une recherche web avait affirmé le contraire (information non
  fiable, corrigée après vérification directe plutôt que fait confiance à la synthèse).
- **Solution retenue** : relais via un autre appareil Shelly du réseau local (jamais de Cloud
  requis pour ce flux). Le webhook natif du détecteur cible `Script.Eval` en GET sur un appareil
  scriptable — RPC Shelly accepte les appels en GET avec paramètres en query string pour
  n'importe quelle méthode, vérifié en direct le 2026-09-01. C'est ce script relais qui fait le
  vrai POST vers ntfy à la place du détecteur.

**Piège des accents dans les paramètres GET**
- `Script.Eval` a besoin d'un script **démarré** (`Script.Start`), pas juste créé — sinon erreur
  `-109 "Script with id 'N' not running!"`.
- Un paramètre `code=` accentué transmis en GET arrive **corrompu** côté appareil : test réel,
  `"Détecteur".length` (9 caractères) a renvoyé `10` au lieu de `9`. Solution : ne jamais faire
  transiter de texte accentué par l'URL du webhook — seule l'adresse MAC (ASCII, sans deux-points)
  y transite ; le nom affiché (accentué) est poussé séparément en **POST** (`Script.Eval` appelé
  depuis l'app, jamais depuis un webhook), qui ne souffre pas de ce problème.

**Limite des 3 scripts actifs par appareil** (voir BACKLOG.md § Scripts Shelly, 2026-08-17)
- S'applique aussi au script relais, qui doit tourner en permanence (jamais transitoire comme la
  coupure sur seuil) — consomme donc un slot de façon définitive sur l'appareil qui l'héberge.
  D'où le choix : relais opportuniste sur plusieurs appareils à la fois plutôt qu'un seul désigné
  (redondance), toujours évictable par un vrai réglage métier (Lot 4b).
