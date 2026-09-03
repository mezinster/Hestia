# CLAUDE.md — Hestia

## Contexte projet

**Hestia** est une application Android de pilotage local d'équipements domotiques Shelly.
Aucun cloud, aucun compte, aucune télémétrie **par défaut**. L'application communique
principalement en HTTP sur le réseau local avec les appareils, via leur API RPC embarquée.

Deux exceptions à ce jour, toutes deux explicites et désactivées par défaut :
- les **notifications instantanées via ntfy** (ajoutées le 2026-07-29). Si l'utilisateur
  l'active dans les Réglages, ce sont les **appareils eux-mêmes** (pas Hestia) qui envoient le
  texte de leurs notifications à `ntfy.sh`, un service tiers de son choix. Cas particulier des
  **détecteurs de fumée** (ajoutés le 2026-08-31) : leur webhook natif ne sait faire qu'une
  requête GET, incompatible avec ntfy (qui exige un POST) — c'est alors un **autre appareil
  Shelly du réseau local** (jamais Hestia, jamais le cloud) qui relaie le texte à sa place, via
  un petit script déployé de façon opportuniste sur un appareil ayant de la place (jamais désigné
  à l'avance, toujours évincé au profit d'un vrai réglage si besoin de la place — voir
  SMOKE-DETECTOR.md § Lot 4).
- le **Cloud Shelly** (ajouté le 2026-08-20) : un interrupteur par appareil (écran Modifier)
  le connecte au cloud officiel Shelly ; si l'utilisateur renseigne en plus une clé de compte
  dans les Réglages, Hestia peut basculer sur l'API Cloud Control de Shelly quand le réseau
  local échoue (repli à distance) — état, consommation et marche/arrêt pour une prise, état
  (alarme, pile) pour un détecteur de fumée, jamais plus : planning, présence, seuils, scripts,
  LED, firmware, ou la coupure de l'alarme (`Smoke.Mute`, strictement locale, aucun équivalent
  cloud), qui restent strictement locaux, cloud activé ou non.

Toujours opt-in, jamais activé sans action explicite, toujours réversible. Toute nouvelle
fonctionnalité qui ferait sortir des données du réseau local doit suivre le même principe :
opt-in, expliquée clairement dans l'écran À propos et dans les Réglages, jamais présentée comme
silencieuse.

- Dépôt : `https://codeberg.org/kapoue/Hestia.git`
- Licence : **GPLv3**
- Package / applicationId : `kapoue.hestia`
- Distribution cible : **F-Droid** (build reproductible, aucune dépendance propriétaire,
  aucun service Google, aucune bibliothèque de tracking)
- Langue : **bilingue depuis le 2026-07-27**. `values/strings.xml` (défaut, sans qualificatif)
  contient l'**anglais** — c'est la langue prioritaire, utilisée pour tout appareil dont la
  langue système n'est ni le français ni l'anglais. `values-fr/strings.xml` contient le
  **français**, utilisé uniquement si la langue système de l'appareil est le français. Android
  choisit automatiquement entre les deux (aucun code de sélection à écrire).
  **Règle permanente à partir de maintenant : toute chaîne visible par l'utilisateur, nouvelle
  ou modifiée, doit être ajoutée dans les DEUX fichiers, systématiquement, dans la même
  livraison — jamais une chaîne anglaise sans son équivalent français ou l'inverse.** Aucune
  chaîne en dur dans le code (`stringResource`/`getString` uniquement). Les mêmes clés doivent
  porter les mêmes espaces de format (`%1$s`, `%2$d`…) dans les deux fichiers.
  Les captures d'écran F-Droid restent en français pour l'instant (voir `fastlane/metadata/`,
  déjà bilingue côté texte depuis la revue linsui — non retouché ici).

## Indépendance vis-à-vis de Shelly

Projet **totalement indépendant de Shelly et d'Allterco Robotics** : ni commandité, ni
sponsorisé, ni approuvé par la marque. Aucune rémunération, aucun matériel fourni ou prêté.
Shelly est citée uniquement à titre de compatibilité technique.

En pratique : ne jamais employer « Shelly » dans le nom de l'application, l'identifiant de
paquet, l'icône ou le titre de la fiche F-Droid. La mention d'indépendance doit figurer dans
l'écran À propos, le README et la description F-Droid (texte exact dans `SPEC-V1.md`).

## Principe fondamental à ne jamais perdre de vue

**Hestia ne stocke aucune configuration d'appareil.**

Toute la configuration métier (planifications, `auto_off`, scripts) est stockée et exécutée
par le **firmware de l'appareil Shelly lui-même**. Hestia n'est qu'une couche de présentation
qui envoie des commandes RPC et lit des états.

Conséquences de conception :
- Une configuration modifiée depuis l'interface web native de l'appareil doit être reflétée
  par Hestia au rafraîchissement suivant. Hestia lit toujours l'état réel de l'appareil,
  ne suppose jamais.
- Les fonctionnalités doivent rester **autonomes après déclenchement** : une fois la commande
  envoyée, l'application peut être fermée, le téléphone éteint ou hors réseau, le comportement
  se poursuit sur l'appareil.
- Ne jamais implémenter de scheduler côté Android (WorkManager, AlarmManager) pour piloter
  un appareil. Ce serait fragile et contraire à l'objectif du projet.

Ce que Hestia stocke en propre, et rien d'autre :
- la liste des appareils connus (nom, adresse IP, type, canal)
- un cache de confort des paramètres de simulation de présence
- les préférences de l'application

Le journal d'activité par appareil (« Dernière activité ») a existé puis a été **retiré le
2026-07-27** : sans tâche de fond permanente, il manquait trop d'événements survenus application
fermée pour rester fiable (voir BACKLOG.md § Écarté). Le journal de diagnostic (§ 7 ci-dessous)
est un mécanisme différent et reste en place.

## Stack technique

Aligné sur les autres applications du portefeuille (Agora, MainTask, Kartapuss, Ignis) :

- **Kotlin**
- **Jetpack Compose** (Material 3)
- **Hilt** + KSP pour l'injection de dépendances
- **MVVM / Clean Architecture** (couches `data` / `domain` / `ui`)
- **Room** pour la persistance locale
- **Kotlin Coroutines / Flow**
- Client HTTP : **OkHttp + kotlinx.serialization**. L'API RPC Shelly est suffisamment simple
  pour un client OkHttp direct, ce qui allège les dépendances pour F-Droid.
  Justifier tout ajout de dépendance.

### Versions du toolchain

| Élément | Valeur | Remarque |
|---|---|---|
| `minSdk` | **30** | Android 11, aligné sur MainTask |
| `compileSdk` | **37** | Android 17 (`suppressUnsupportedCompileSdk=37.0` en attendant que l'AGP le liste) |
| `targetSdk` | **37** | Android 17 |
| AGP | **9.1.0** | **built-in Kotlin** : ne PAS appliquer le plugin `kotlin.android`, le `jvmTarget` reprend `compileOptions.targetCompatibility` |
| Gradle | **9.3.1** | minimum imposé par AGP 9.1.0 / Android Studio |
| Kotlin | **2.3.10** | aligné sur KSP (Kotlin 2.3.21 existe mais aucun KSP correspondant n'est encore publié) |
| KSP | **2.3.10** | suit strictement la version de Kotlin |
| Compose BOM | **2026.06.00** | |
| Hilt | **2.60.1** | |
| Room | **2.8.0** | schéma des 3 entités figé dès le lot 1 |
| JDK | **17** | |

Valeurs **validées le 2026-07-19** (build + test sur appareil physique). L'accès HTTP en clair
vers l'API RPC locale Shelly est autorisé via `res/xml/network_security_config.xml`
(`cleartextTrafficPermitted="true"`), sans quoi tout appel échoue en `IOException`.
Vérifier périodiquement si des versions stables plus récentes existent (notamment quand KSP
rattrape Kotlin), et le signaler avant de les adopter.

## Permissions Android

**Strictement le minimum.** V1 :

- `android.permission.INTERNET`
- `android.permission.ACCESS_LOCAL_NETWORK` — **permission runtime**, obligatoire depuis
  Android 17 pour tout trafic vers une adresse du réseau local, y compris avec une adresse IP
  saisie manuellement. Elle appartient au groupe `NEARBY_DEVICES`.

C'est tout. Pas de Bluetooth, pas de localisation, pas de stockage, pas de démarrage au boot,
pas de notifications en V1. Toute demande de permission supplémentaire doit être explicitement
justifiée et validée avant implémentation.

Règles de comportement pour `ACCESS_LOCAL_NETWORK` :
- **Ne pas la demander au lancement.** L'application doit s'ouvrir, se naviguer et permettre
  de consulter l'À propos et les Réglages sans aucun popup.
- La demander au premier contact réseau réel (test de connexion lors de l'ajout d'un
  appareil, ou première commande), **précédée d'un écran d'explication rédigé par nous** :
  pourquoi elle est nécessaire, ce qu'elle permet, et l'engagement qu'aucune adresse autre
  que celles saisies par l'utilisateur n'est contactée.
- Si elle est refusée, l'application reste utilisable : liste des appareils, réglages,
  à propos. Les tuiles passent en état « permission requise » avec un bouton pour relancer
  la demande. **Jamais d'écran bloquant.**
- Afficher l'état de la permission dans les Réglages, avec un bouton ouvrant la page système
  de l'application. Android ne permet pas à une application de révoquer ses propres
  permissions par API : il ne peut donc pas y avoir de véritable interrupteur interne.

Les appareils sont ajoutés **manuellement par saisie d'adresse IP**. Aucun scan réseau,
aucune découverte mDNS ou BLE en V1.

## Versionnement

- `versionName` au format **SemVer** `x.y.z` : majeur (refonte), mineur (ajout de
  fonctionnalités), correctif.
- **Ne jamais employer le nombre 13**, à aucune position (`1.13.0`, `13.0.0`, `2.1.13`…).
  Passer directement de `1.12.x` à `1.14.0`.
- `versionCode` : entier strictement croissant à chaque publication F-Droid. Il peut sauter
  des valeurs, il n'a pas à suivre le `versionName`.

## Compilation et tests

**Claude Code n'exécute pas les builds.** Fournir les commandes, l'utilisateur les lance
lui-même dans son terminal (iTerm2, macOS) et colle le résultat. Cela économise du contexte
et évite les builds à l'aveugle.

```bash
./gradlew assembleDebug          # APK de debug
./gradlew installDebug           # compile et installe sur l'appareil branché
./gradlew testDebugUnitTest      # tests unitaires
./gradlew lint                   # analyse statique
```

APK produit dans `app/build/outputs/apk/debug/`.

Matériel de test :
- **Sony sous Android 11** (API 30) — cible principale, validation du minSdk
- **Pixel 10 Pro sous Android 17** (API 37) — validation de `ACCESS_LOCAL_NETWORK`
- Appareil Shelly de référence : **Plug M Gen3**, IP `192.168.1.96`, sans authentification

Voir `TESTS.md` pour la checklist de validation manuelle.

## Ton et conventions

- Commentaires et documentation du code en français.
- Nommage du code (classes, variables) en anglais, conventions Kotlin standard.
- Les messages d'erreur affichés à l'utilisateur expliquent quoi faire, pas ce qui a planté
  techniquement. Exemple : « Appareil injoignable. Vérifie qu'il est allumé et sur le même
  réseau Wi-Fi. » plutôt que « SocketTimeoutException ».
- L'état d'un appareil est **toujours accompagné d'un libellé texte**, jamais signalé par la
  seule couleur de la LED. Ne pas supprimer ce libellé lors d'une refonte visuelle.
- Pas de bibliothèque d'analytics, de crash reporting distant, ni de vérification de mise à
  jour réseau. Jamais.
- **Instrumenter systématiquement le journal de diagnostic** (voir `SPEC-V1.md` § 7) : toute
  navigation, action utilisateur, appel RPC et erreur doit y laisser une trace exploitable.
  Ce journal reste actif en production et sert de base au diagnostic des bugs remontés.
  Il est local, borné en taille, et n'est jamais envoyé automatiquement. Aucun mot de passe
  ne doit y figurer.

## Méthode de travail

Développement **lot par lot avec validation explicite** entre chaque lot.
Ne pas enchaîner sur le lot suivant sans accord. Voir `SPEC-V1.md` pour le découpage.
