# Hestia — Spécifications V1

## 1. Périmètre

Application Android de pilotage local d'appareils **Shelly Gen2+** (Gen2, Gen3, Gen4).
Matériel de référence : **Shelly Plug M Gen3**, IP `192.168.1.96`, sans authentification.

Hors périmètre V1 (mais l'architecture ne doit pas les rendre impossibles) :
- authentification par mot de passe sur l'appareil (lot ultérieur, champ déjà prévu)
- traduction anglaise
- autres marques que Shelly
- Shelly Gen1 (API différente, non RPC) — non supporté, ne pas coder
- Matter — non pertinent (nécessiterait un contrôleur Matter, contraire au principe direct/local)
- découverte automatique d'appareils (mDNS, BLE)
- accessibilité renforcée (contrastes daltonisme, TalkBack complet) — décision assumée,
  à réévaluer si F-Droid en fait un critère

---

## 2. Modèle de données (Room)

### Entité `Device`

Un enregistrement par **canal**, pas par appareil physique. Un Shelly Plug M n'a qu'un canal
(`switchId = 0`) ; un Shelly Pro 4PM en a quatre (`switchId` 0 à 3) derrière une seule IP.
Chaque canal apparaît comme une tuile distincte.

| Champ | Type | Notes |
|---|---|---|
| `id` | Long (PK, auto) | |
| `name` | String | Nom donné par l'utilisateur (ex. « Prise scooter ») |
| `ipAddress` | String | Saisie manuelle, validée au format IPv4 |
| `switchId` | Int | Identifiant du canal, défaut `0` |
| `type` | Enum `DeviceType` | `PLUG`, `LAMP`, `SENSOR` |
| `driver` | Enum `DriverType` | `SHELLY_GEN2` (seule valeur en V1) |
| `model` | String? | Modèle rapporté par `Shelly.GetDeviceInfo`, informatif |
| `position` | Int | Ordre d'affichage dans la grille |
| `createdAt` | Long | |

Contrainte d'unicité sur le couple (`ipAddress`, `switchId`).

### Entité `PresenceConfig`

Une par appareil. **Cache local de confort** permettant de réafficher les paramètres choisis.
La source de vérité reste le script déployé sur l'appareil : au chargement, vérifier via
`Script.List` si le script est réellement présent et actif, et refléter l'état réel.

| Champ | Type | Notes |
|---|---|---|
| `id` | Long (PK, auto) | |
| `deviceId` | Long (FK → Device, CASCADE) | |
| `startHour` / `startMinute` | Int | Début de plage (ex. 19:00) |
| `endHour` / `endMinute` | Int | Fin de plage (ex. 23:00) |
| `randomMarginMinutes` | Int | Marge aléatoire, défaut 20 |
| `shellyScriptId` | Int? | ID retourné par `Script.Create` |
| `enabled` | Boolean | Dernier état connu |

### Entité `ActivationLog`

Historique local, affiché dans l'écran de détail. Purement informatif, aucune donnée sortante.

| Champ | Type | Notes |
|---|---|---|
| `id` | Long (PK, auto) | |
| `deviceId` | Long (FK → Device, CASCADE) | |
| `timestamp` | Long | |
| `action` | Enum | `TURNED_ON`, `TURNED_OFF`, `TIMER_STARTED`, `TIMER_CANCELLED`, `PRESENCE_DEPLOYED`, `PRESENCE_STOPPED` |
| `detail` | String? | ex. durée du minuteur |

Purger automatiquement les entrées de plus de 30 jours.

---

## 3. API Shelly Gen2+ — référence

Protocole **JSON-RPC 2.0** sur HTTP :

```
POST http://<ip>/rpc
Content-Type: application/json

{"id": 1, "method": "Switch.Set", "params": {"id": 0, "on": true}}
```

Réponse : objet JSON avec `result`, ou `error` contenant `code` et `message`.

### Méthodes utilisées en V1

| Besoin | Méthode | Paramètres |
|---|---|---|
| État complet | `Shelly.GetStatus` | — |
| Infos appareil (modèle, gen, firmware) | `Shelly.GetDeviceInfo` | — |
| Liste des composants (détection des canaux) | `Shelly.GetComponents` | — |
| Allumer / éteindre | `Switch.Set` | `{"id": N, "on": true\|false}` |
| Lire l'état d'un canal | `Switch.GetStatus` | `{"id": N}` |
| Lire la config d'un canal | `Switch.GetConfig` | `{"id": N}` |
| Configurer le minuteur | `Switch.SetConfig` | voir ci-dessous |
| Créer un script | `Script.Create` | `{"name": "hestia_presence"}` → `{"id": N}` |
| Envoyer le code | `Script.PutCode` | `{"id": N, "code": "…", "append": false}` |
| Activer | `Script.SetConfig` | `{"id": N, "config": {"enable": true}}` |
| Démarrer | `Script.Start` | `{"id": N}` |
| Lister | `Script.List` | — |
| Supprimer | `Script.Delete` | `{"id": N}` |

### Détection des capacités

À l'ajout d'un appareil, interroger `Shelly.GetDeviceInfo` puis `Shelly.GetComponents` pour
déduire : la génération (rejeter si Gen1), le nombre de canaux `switch`, la présence du
moteur de scripts, la présence de la mesure de puissance.

**Ne pas maintenir de catalogue de modèles Shelly en dur.** Interroger l'appareil permet de
supporter des modèles sortis après l'application.

### Minuteur « marche pour X »

Fonction native `auto_off` du firmware, exécutée par l'appareil en autonomie :

```json
{"id": 1, "method": "Switch.SetConfig",
 "params": {"id": 0, "config": {"auto_off": true, "auto_off_delay": 3600}}}
```

Puis `Switch.Set` à `true`. `auto_off_delay` est en **secondes**. Le compte à rebours est
géré par l'appareil ; le téléphone n'a aucun rôle après l'envoi.

Le temps restant se lit dans `Switch.GetStatus`. **À valider sur le matériel réel** :
inspecter la réponse brute sur la Plug M Gen3 avec un minuteur actif et adapter le parsing.
Ne pas supposer les noms de champs.

Annuler : `Switch.SetConfig` avec `{"auto_off": false}`, puis `Switch.Set` à `false` si
l'utilisateur le demande.

### Erreurs et robustesse

- Timeout court (3 à 5 s) — c'est du réseau local.
- Codes d'erreur RPC négatifs (`-105` requête mal formée, `-114` ressource indisponible…) :
  les journaliser, afficher un message utilisateur actionnable.
- Ne jamais retenter automatiquement en boucle. Le réessai est déclenché par l'utilisateur.

---

## 4. Simulation de présence

### Principe

L'application **génère un script JavaScript** à partir des paramètres saisis, le pousse sur
l'appareil via `Script.Create` + `Script.PutCode`, puis l'active. Le script s'exécute ensuite
en permanence sur l'appareil, sans aucune dépendance au téléphone.

### Convention de nommage

Le script créé par Hestia est nommé `hestia_presence`. Au chargement de l'écran, utiliser
`Script.List` pour le détecter : cela permet de retrouver l'état réel même après
réinstallation de l'application ou changement de téléphone.

Ne jamais supprimer ou modifier un script portant un autre nom — l'utilisateur peut avoir
ses propres scripts sur l'appareil.

### Conflit avec le minuteur

Un script de présence actif et un minuteur `auto_off` se disputent le même relais.

Comportement retenu : **avertir et laisser choisir.** Si l'utilisateur lance un minuteur sur
un appareil dont le script de présence est actif, afficher un dialogue expliquant que la
simulation de présence pourra reprendre la main sur l'appareil pendant le minuteur, avec
trois issues : annuler, lancer quand même, ou arrêter la simulation puis lancer le minuteur.

Ne jamais arrêter un script silencieusement.

### Modèle de script à générer

Squelette indicatif, **à valider et ajuster sur le matériel réel**. Le moteur JS des Shelly
est restreint : vérifier le support de `Date`, de `Timer.set` et de `Math.random` sur le
firmware de la Plug M Gen3 avant de figer l'implémentation.

```javascript
// Généré par Hestia — simulation de présence
// Plage : 19:00 -> 23:00, marge aléatoire +/- 20 min
let CFG = {
  startMin: 19 * 60,
  endMin: 23 * 60,
  marginMin: 20,
  switchId: 0
};

let planned = { onAt: null, offAt: null, day: null };

function rnd(m) { return Math.floor(Math.random() * (2 * m + 1)) - m; }

function planDay(day) {
  planned.day = day;
  planned.onAt = CFG.startMin + rnd(CFG.marginMin);
  planned.offAt = CFG.endMin + rnd(CFG.marginMin);
}

Timer.set(60000, true, function () {
  let sys = Shelly.getComponentStatus("sys");
  if (!sys || !sys.unixtime) return;

  let d = new Date(sys.unixtime * 1000);
  let now = d.getHours() * 60 + d.getMinutes();
  let day = Math.floor(sys.unixtime / 86400);
  if (planned.day !== day) planDay(day);

  let status = Shelly.getComponentStatus("switch", CFG.switchId);
  if (!status) return;

  let inWindow = now >= planned.onAt && now < planned.offAt;
  if (inWindow && !status.output) Shelly.call("Switch.Set", { id: CFG.switchId, on: true });
  if (!inWindow && status.output) Shelly.call("Switch.Set", { id: CFG.switchId, on: false });
});
```

### Points de vigilance

- **Dépendance à l'heure de l'appareil.** Le script repose sur l'horloge interne, synchronisée
  par NTP. Un appareil sans accès réseau durable peut dériver ou perdre l'heure après une
  coupure de courant. Afficher l'heure rapportée par l'appareil et **avertir visuellement si
  l'écart avec l'heure du téléphone dépasse 2 minutes**. Ce cas est concret pour l'appareil
  de référence, situé dans un garage à couverture Wi-Fi incertaine.
- Un script activé redémarre automatiquement au boot de l'appareil si `enable: true`.
- Vérifier la limite de taille acceptée par `Script.PutCode` et découper l'envoi si besoin
  via le paramètre `append`.
- Toujours relire l'état réel après déploiement plutôt que de supposer le succès.

---

## 5. Interface

### Thème

Suit le **thème système** (clair ou sombre). Les deux variantes sont conçues et testées dès
la V1. Le choix manuel est prévu pour un lot ultérieur.

### Direction visuelle : « tableau électrique »

Métaphore structurelle, pas littérale. Une grille de circuits numérotés, chaque tuile
représentant un canal quel que soit son type.

- Tuiles rectangulaires à angles peu arrondis (2 dp), numérotées `01`, `02`… en monospace
- Une **LED d'état** (petit disque coloré) par tuile, **toujours accompagnée d'un libellé
  texte** — ne jamais laisser la couleur porter seule l'information
- Un **interrupteur à bascule** stylisé (rectangulaire, pas un toggle Material arrondi)
- Un liseré fin en tête de grille (« barre omnibus »), décoratif
- Police **monospace** réservée aux valeurs techniques : adresses IP, durées, compteurs

### Palette

Dérivée de l'icône : bleu profond et orange, fond craie en clair.

Thème clair :

| Rôle | Valeur |
|---|---|
| Fond | `#F4F3EF` |
| Surface (tuile) | `#FCFBF8` |
| Bordure | `#DAD7CE` |
| Texte principal | `#14171A` |
| Texte secondaire | `#5A6167` |
| Accent | `#1B3AAB` |

Thème sombre — le bleu profond devient illisible sur fond sombre, il est remplacé par une
variante lumineuse :

| Rôle | Valeur |
|---|---|
| Fond | `#14161C` |
| Surface (tuile) | `#1D212A` |
| Bordure | `#2C313C` |
| Texte principal | `#ECEAE4` |
| Texte secondaire | `#949AA4` |
| Accent | `#6E8FF5` |

### Couleurs d'état — deux jeux distincts

**Ne pas partager les mêmes valeurs entre les deux thèmes.** Les couleurs vives calibrées
pour un fond sombre passent sous le seuil de contraste sur fond clair, en particulier pour
le texte (compte à rebours, libellés).

Distinguer deux usages : la **LED** peut rester vive dans les deux thèmes, ce n'est pas du
texte ; le **texte et les libellés** utilisent la variante assombrie en thème clair.

| État | LED sombre | Texte sombre | LED claire | Texte clair |
|---|---|---|---|---|
| Actif | `#4ADE80` | `#4ADE80` | `#22B04B` | `#178C46` |
| Repos | `#5A616B` | `#949AA4` | `#9AA0A8` | `#5A6167` |
| Minuté | `#F5843F` | `#F5843F` | `#E8622C` | `#C24E1B` |
| Hors ligne | `#F0716E` | `#F0716E` | `#D8342A` | `#C0342B` |

Viser un contraste texte d'au moins 4.5:1 dans les deux thèmes.

### Icône de l'application

Symbole marche/arrêt (IEC 60417, domaine public) sur fond craie `#F4F3EF` :
anneau ouvert et barre verticale en bleu `#1B3AAB`, complétés d'un **point orange
`#E8622C`** en haut à droite.

Le point orange est un **élément graphique fixe**, à décrire comme un voyant de mise sous
tension. Il ne reflète pas l'état réel d'un appareil et ne doit jamais être animé ou modifié
dynamiquement.

Icône adaptative Android : le symbole occupe la zone sûre centrale, le fond craie remplit
la couche d'arrière-plan. Une version monochrome est fournie pour le thème d'icônes
Android 13+.

**Le logo conserve son fond craie dans les deux thèmes de l'application**, y compris en
thème sombre où il apparaît comme une pastille claire. Choix assumé : c'est un logo, pas un
élément d'interface. Ne pas produire de variante détourée ni recolorée.

Fichiers fournis dans `assets/` :

| Fichier | Usage |
|---|---|
| `ic_launcher_full.svg` | icône complète, source vectorielle |
| `ic_launcher_background.svg` | couche d'arrière-plan de l'icône adaptative |
| `ic_launcher_foreground.svg` | couche de premier plan de l'icône adaptative |
| `ic_launcher_monochrome.svg` | couche monochrome (Android 13+) |
| `feature_graphic.svg` | bannière F-Droid, source vectorielle |
| `png/` | exports PNG (48 à 512 px, bannière 1024 × 500) |

Privilégier les sources SVG converties en `VectorDrawable` plutôt que les PNG, sauf pour la
bannière F-Droid qui doit être livrée en PNG.

### Bannière F-Droid (1024 × 500)

Icône et nom « Hestia » à gauche, accompagnés de la baseline « Pilotage local, sans cloud ni
compte » et de la mention `GPLv3 · Shelly Gen2+`. À droite, un aperçu de la grille de tuiles.
Fond craie en cohérence avec l'icône.

### Navigation

Barre de navigation basse à **trois onglets** : Tableau, Réglages, À propos.

Les écrans Détail et Configuration de présence sont des **destinations empilées** par-dessus
l'onglet courant, avec bouton retour. Ce sont des écrans contextuels, pas des sections.

**Pas de modale pour les écrans de configuration** : le bouton retour Android est géré
nativement, le contenu est trop dense, et c'est plus simple à tester.

Exception : le sélecteur de durée « Perso » s'ouvre en **bottom sheet**, car c'est une action
rapide qui gagne à garder le contexte de la tuile visible.

**Toute page comportant des champs modifiables** affiche un bouton retour et, si des
modifications ne sont pas enregistrées, un dialogue de confirmation : « Des modifications
n'ont pas été enregistrées. Les abandonner ? » avec les issues Abandonner / Continuer.

### Écran 1 — Tableau (accueil)

- Grille 2 colonnes de tuiles
- Chaque tuile : numéro, icône selon type, nom, libellé d'état, LED, interrupteur
- Si un minuteur est actif : compte à rebours en monospace, décrémenté localement à la
  seconde à partir de la dernière lecture, resynchronisé à chaque rafraîchissement
- Appui sur l'interrupteur : bascule immédiate. Appui sur le corps de la tuile : écran de détail
- **Appareil injoignable** : LED rouge, libellé « Hors ligne », bouton **« Réessayer »**
  directement dans la tuile. Les autres tuiles restent pleinement fonctionnelles
- **Permission refusée** : tuiles en état « Permission requise » avec un bouton relançant
  la demande. Pas d'écran bloquant
- État vide : message d'invitation avec accès direct à l'ajout d'appareil
- **Rafraîchissement** : interrogation toutes les **5 secondes** tant que l'écran est au
  premier plan, arrêtée dès la mise en arrière-plan (lier au cycle de vie), plus un
  **pull-to-refresh** manuel

### Écran 2 — Détail d'un appareil

- En-tête : icône, nom, adresse IP en monospace, modèle rapporté par l'appareil
- **Marche pour** : boutons `1h` / `2h` / `3h` / `Perso`. Section masquée si l'appareil ne
  supporte pas `auto_off`
- **Simulation de présence** : état actuel, accès à la configuration. Section masquée si
  l'appareil ne supporte pas le scripting
- **Dernière activité** : quelques entrées de `ActivationLog`
- Avertissement visible si l'horloge de l'appareil est désynchronisée

### Sélecteur de durée « Perso »

Bottom sheet reprenant la métaphore d'une **minuterie mécanique** : deux rouleaux crantés
que l'on fait défiler verticalement au doigt.

- Rouleau des heures : `0` à `23`
- Rouleau des minutes : `0` à `59`
- Durée minimale acceptée : **1 minute**. Valider avant envoi
- Affichage de la durée résultante en monospace sous les rouleaux
- Retour haptique léger à chaque cran, si disponible

### Écran 3 — Configuration de la simulation de présence

- Heure de début et heure de fin
- Marge aléatoire en minutes
- Bouton **« Déployer sur l'appareil »** / **« Arrêter »**
- Après déploiement, relire l'état réel et le confirmer à l'écran

### Écran 4 — Réglages

- **Appareils** : liste avec nom, IP, canal, indicateur de connectivité
  - Ajout : nom, adresse IP, type. Test de connexion avant enregistrement
    (`Shelly.GetDeviceInfo`) pour vérifier qu'il s'agit bien d'un Shelly Gen2+.
    Si l'appareil expose plusieurs canaux, proposer de les ajouter tous
  - Modification, suppression, réordonnancement
  - Un bouton par appareil ouvrant `http://<ip>` dans le navigateur système
    (intent `ACTION_VIEW`), pour accéder à l'interface web embarquée de l'appareil
- **Sauvegarde** : voir section dédiée ci-dessous
- **Permission réseau local** : état actuel (accordée / refusée) et bouton ouvrant la page
  système de l'application
- **Apparence** : mention « suit le thème système ». Choix manuel prévu ultérieurement
- **Sécurité** : champ mot de passe présent mais **désactivé**, mention
  « Disponible prochainement »

### Écran 5 — À propos

- Nom, icône, numéro de version
- **Origine du nom** :
  > Hestia est, dans la mythologie grecque, la déesse du foyer et du feu domestique.
  > Elle veille sur la maison, discrètement et sans jamais la quitter — ce qui correspond
  > assez bien à une application qui reste chez vous.
- Positionnement : pilotage 100 % local, aucune donnée ne quitte le réseau, aucun compte,
  aucun traçage
- **Explication de la permission réseau local**, en langage clair :
  > Depuis Android 17, une application doit demander votre autorisation pour communiquer
  > avec les appareils de votre réseau local. Hestia en a besoin pour parler à vos appareils.
  > Elle ne contacte que les adresses que vous avez saisies vous-même, n'explore pas votre
  > réseau et n'envoie rien à l'extérieur.
- **Mention d'indépendance**, à reprendre à l'identique dans le README et la fiche F-Droid :
  > Hestia est un projet indépendant, sans aucun lien avec Shelly ni avec Allterco Robotics.
  > Ce projet n'est ni commandité, ni sponsorisé, ni approuvé par Shelly. Aucune rémunération
  > ni aucun matériel n'a été fourni ou prêté par la marque. Shelly est une marque déposée de
  > son propriétaire respectif ; elle n'est citée qu'à titre de compatibilité technique.
- Licence **GPLv3** avec lien vers le texte
- Lien vers `codeberg.org/kapoue/Hestia`
- **QR code de partage**, généré localement (pas d'appel réseau, pas de service tiers —
  bibliothèque libre embarquée type ZXing, ou génération maison si l'ajout de dépendance
  n'est pas justifié)
- Mention : « Compatible Shelly Gen2 et ultérieur. Support d'autres marques et version
  anglaise prévus dans de prochaines versions. »

---

## 6. Sauvegarde et restauration

Accessible depuis les **Réglages**. Export et import d'un fichier **JSON** couvrant
**l'intégralité des données de l'application** : liste des appareils, configurations de
présence en cache, préférences. Le journal d'activité peut être exclu (volumineux et sans
valeur de restauration) — le documenter dans le fichier.

Format :

```json
{
  "format": "hestia-backup",
  "formatVersion": 1,
  "appVersion": "1.0.0",
  "exportedAt": "2026-07-18T14:30:00Z",
  "devices": [ ... ],
  "presenceConfigs": [ ... ],
  "preferences": { ... }
}
```

Règles :
- Export via `ACTION_CREATE_DOCUMENT`, import via `ACTION_OPEN_DOCUMENT` (Storage Access
  Framework) — **aucune permission de stockage nécessaire**
- Import : valider `format` et `formatVersion` avant tout traitement, refuser proprement un
  fichier inconnu ou corrompu
- **Un seul mode d'import : le remplacement intégral.** Les données existantes sont écrasées.
  Pas de fusion — les règles d'arbitrage entre doublons seraient une source de complexité et
  d'ambiguïté sans bénéfice réel
- Avertir clairement **avant** l'import, par un dialogue de confirmation : « L'import va
  remplacer tous vos appareils et réglages actuels. Cette action est irréversible. »
  avec les issues Annuler / Remplacer
- **Règle permanente, valable pour toutes les versions futures** : toute création,
  modification ou suppression d'une fonctionnalité ayant des données persistées doit être
  répercutée sur l'export et l'import dans le même lot de développement. Incrémenter
  `formatVersion` en cas de changement incompatible et gérer la migration à l'import.
  Ne jamais livrer une fonctionnalité qui casserait silencieusement une sauvegarde existante

---

## 7. Journal de diagnostic

Outil de support permettant à l'utilisateur d'envoyer un contexte technique exploitable en
cas de bug. **Actif en permanence, y compris en production.**

### Accès

**Cinq appuis successifs sur le logo Hestia** dans l'écran À propos. Aucun autre point
d'entrée, aucune mention dans l'interface, **aucun retour visuel pendant la séquence
d'appuis** : c'est un outil de support, pas une fonctionnalité affichée.

Réinitialiser le compteur d'appuis après 2 secondes d'inactivité.

### Stockage

Table Room dédiée, exploitée comme un **tampon circulaire à taille fixe** : au-delà de
**2 000 entrées**, les plus anciennes sont supprimées. À environ 150 octets par ligne, cela
représente moins de 500 Ko — négligeable, et surtout borné, ce qui permet de laisser le
journal actif indéfiniment sans surveillance.

Écriture asynchrone, hors du fil principal, sans jamais bloquer l'interface.
Une écriture de journal qui échoue ne doit jamais faire échouer l'action en cours.

### Contenu

En-tête généré au moment du partage :
- version de l'application (`versionName` + `versionCode`)
- version d'Android et niveau d'API
- modèle et fabricant de l'appareil
- langue et thème actifs
- état de la permission `ACCESS_LOCAL_NETWORK`
- nombre d'appareils configurés

Puis les entrées horodatées, du plus récent au plus ancien :
- navigation : écran ouvert, écran quitté
- actions utilisateur : appui sur un interrupteur, lancement d'un minuteur, déploiement ou
  arrêt d'un script, import, export
- appels RPC : méthode appelée, appareil visé, code de retour, durée
- erreurs : type, message, contexte
- changements d'état observés sur les appareils

Format : une ligne par entrée, texte brut, horodatage ISO 8601 en tête.

```
2026-07-18T14:22:31Z  INFO   ui       Écran Détail ouvert (device=1)
2026-07-18T14:22:33Z  INFO   rpc      Switch.Set id=0 on=true → 200 (128 ms)
2026-07-18T14:22:38Z  WARN   rpc      Shelly.GetStatus → timeout après 5000 ms
```

**Ne jamais journaliser de mot de passe**, y compris lorsque l'authentification appareil
sera implémentée : remplacer par `***`.

### Partage

Partage via `ACTION_SEND` (feuille de partage système Android), en texte brut ou en pièce
jointe `.txt` selon la taille. Aucun envoi réseau propre à l'application, aucun serveur.

Avant partage, afficher un écran de prévisualisation indiquant :
- le contenu exact qui sera partagé, consultable par défilement
- une phrase d'avertissement : « Ce journal contient les adresses IP locales et les noms de
  vos appareils. Ne le partagez qu'avec quelqu'un de confiance. »
- un bouton **Partager**

**Pas de bouton d'effacement**, choix assumé : le tampon circulaire s'auto-purge, rien ne
s'accumule. À noter que vider le cache système de l'application ne supprime pas le journal
(il vit dans la base Room, donc dans les données) — seul « Effacer les données » le ferait,
au prix de la perte des appareils et des réglages.

---

## 8. Widget d'écran d'accueil

Widget **1×1** permettant de piloter un canal sans ouvrir l'application, dans la lignée
d'Ignis. Construit avec **Jetpack Glance**.

### Contenu

- **Titre** : le nom du canal, tronqué si nécessaire. Appui → ouvre l'application sur
  l'écran de détail de ce canal
- **Corps** : LED d'état et interrupteur. Appui → bascule le canal
- Un widget est lié à **un canal choisi à la configuration** du widget. Plusieurs widgets
  peuvent coexister pour des canaux différents

### États affichés

Mêmes couleurs et libellés que les tuiles du Tableau, thèmes clair et sombre suivis.
S'ajoutent deux états propres au widget :
- **En cours** : pendant l'appel RPC, indication visuelle brève
- **Permission requise** : si `ACCESS_LOCAL_NETWORK` n'est pas accordée, l'appui ouvre
  l'application plutôt que de tenter un appel voué à l'échec

### Contraintes techniques à traiter

- Un widget ne peut pas exécuter d'appel réseau dans son processus de rendu. L'appui doit
  déclencher un `Worker` (WorkManager) ou un `BroadcastReceiver` qui réalise l'appel RPC
  puis demande la mise à jour du widget
- **Vérifier le comportement de `ACCESS_LOCAL_NETWORK` depuis un contexte d'arrière-plan**
  sur Android 17. Point d'incertitude réel : à valider tôt sur le Pixel avant de bâtir
  dessus. Si l'accès est refusé hors premier plan, replier le widget sur l'ouverture de
  l'application
- Pas de rafraîchissement périodique automatique : l'état est mis à jour après une action et
  à l'ouverture de l'application. Un polling depuis un widget viderait la batterie pour un
  bénéfice faible
- Le canal lié doit survivre à une suppression d'appareil : si le canal n'existe plus,
  afficher un état « Appareil supprimé » plutôt que de planter

### Journal

Les actions déclenchées depuis le widget sont journalisées comme les autres, avec une
mention explicite de l'origine (`widget`).

---

## 9. Découpage en lots

Validation explicite requise à la fin de chaque lot avant de passer au suivant.

**Lot 1 — Fondations**
Projet Gradle, Hilt, Room, thèmes clair et sombre, navigation à trois onglets, écrans vides.
Modèle de données et DAO. Client RPC Shelly (`Shelly.GetDeviceInfo`, `Shelly.GetComponents`,
`Switch.GetStatus`). Gestion de la permission `ACCESS_LOCAL_NETWORK` avec écran d'explication.
Écran Réglages : ajout, modification, suppression d'appareil avec test de connexion et
détection des canaux.

**Lot 2 — Tableau et pilotage**
Écran Tableau : grille, tuiles, LED et libellés, interrupteur, états, hors ligne avec bouton
Réessayer, permission refusée, état vide, polling 5 s lié au cycle de vie, pull-to-refresh.

**Lot 3 — Minuteur**
Écran de détail, boutons `1h` / `2h` / `3h`, bottom sheet à rouleaux pour « Perso »,
`Switch.SetConfig` avec `auto_off`, affichage et annulation du compte à rebours,
journal d'activité.

**Lot 4 — Simulation de présence**
Génération et déploiement du script, écran de configuration, détection d'un script existant,
gestion du conflit avec le minuteur, contrôle de dérive d'horloge.

**Lot 5 — Sauvegarde et journal de diagnostic**
Export et import JSON depuis les Réglages, remplacement intégral avec dialogue d'avertissement,
validation du format. Journal de diagnostic : tampon circulaire, instrumentation des écrans,
des actions et des appels RPC, accès par cinq appuis sur le logo, prévisualisation et partage.

**Lot 6 — Widget**
Widget 1×1 en Jetpack Glance, écran de configuration du canal lié, action de bascule via
Worker, gestion des états, validation du comportement de la permission en arrière-plan.

**Lot 7 — Finitions**
Écran À propos avec QR code, icône adaptative et version monochrome, bannière F-Droid,
relecture des chaînes, vérification des contrastes, métadonnées fastlane, README,
préparation de la première version taguée.

---

## 10. Critères d'acceptation V1

- L'application ne déclare que `INTERNET` et `ACCESS_LOCAL_NETWORK`
- Aucun appel réseau vers une destination autre qu'une adresse saisie par l'utilisateur —
  vérifiable par lecture du code
- Aucun popup de permission au premier lancement
- Permission refusée : l'application reste navigable, aucun écran bloquant
- Fermer l'application ou éteindre le téléphone n'interrompt ni un minuteur ni une
  simulation de présence en cours
- Une modification faite depuis l'interface web de l'appareil est visible après rafraîchissement
- Un appareil hors ligne n'empêche pas d'utiliser les autres
- Un état n'est jamais signalé par la seule couleur
- Un export puis un import sur une installation vierge restitue l'état complet
- L'import avertit clairement avant d'écraser les données existantes
- Le journal de diagnostic est accessible, borné en taille, et son partage affiche
  l'avertissement sur les adresses IP
- Aucun mot de passe n'apparaît dans le journal
- L'application est lisible et cohérente dans les deux thèmes
- Le projet compile en build reproductible sans dépendance propriétaire
- Le widget bascule bien le canal lié et ouvre l'application depuis son titre
- Aucun numéro de version ne contient 13
