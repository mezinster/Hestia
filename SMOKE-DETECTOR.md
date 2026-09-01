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

- **Lot 4 — Notifications ntfy.** Webhooks natifs Shelly (pas de script) sur `smoke.alarm`,
  `smoke.alarm_off`, `smoke.alarm_test`, et batterie sous 15 %.
  → *Testable : déclencher un test, vérifier que la notif arrive sur le téléphone.*

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
- `Temperature.GetStatus` → température de la pièce
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

**Réinitialisation / réveil (bouton physique)**
- 3 appuis brefs : réveil / mode configuration 2 min, sans rien effacer
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
