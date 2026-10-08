# Publier une version du fork (mezinster/Hestia)

[English](RELEASING.md) · **Français** · [Русский](RELEASING.ru.md)

Procédure du mainteneur du fork. Les testeurs ont leur propre guide : [TESTING.fr.md](TESTING.fr.md).

## 1. Branches

Dépôt principal : <https://github.com/mezinster/Hestia> (`origin`). <https://codeberg.org/mezinster/Hestia>
est un **miroir en lecture seule** alimenté par `.github/workflows/codeberg-mirror.yml` (voir § 4bis) :
ne jamais y pousser ni y fusionner quoi que ce soit. L'amont reste `upstream` =
<https://codeberg.org/kapoue/Hestia> (lecture seule).

- `main` : miroir exact de `upstream/main` (kapoue/Hestia). Jamais de commit propre au fork.
- `fork/main` : branche d'intégration = amont + branches de fonctionnalité du fork + configuration
  de publication (bloc en fin de `app/build.gradle.kts`, `app/src/release/AndroidManifest.xml`,
  `scripts/fork/`, `.github/`, `docs/fork/`). **Les publications sont taguées uniquement ici.**
- `feature/*` : une branche par fonctionnalité, fusionnée dans `fork/main` une fois prête.

Synchroniser avec l'amont :

```bash
git fetch upstream
git switch main && git merge --ff-only upstream/main && git push origin main
git switch fork/main && git merge main
git merge feature/<nouvelle-fonctionnalité>     # le cas échéant
./gradlew testDebugUnitTest
git push origin fork/main
```

La configuration du fork n'ajoute que des lignes (jamais de modification de lignes amont) : une
synchronisation ne devrait pas produire de conflit à cause d'elle.

Conflits attendus à la synchronisation (plannings des variateurs, lot C3) : `ShellyRpcClient.scheduleCreate`
et `DeviceRepository.reconstructPlannings` ont une signature générique (`control, channelId` au lieu de
`switchId`) ; `applyIfAlreadyActive`/`deletePlanning` appellent `setChannelAt`. Si l'amont modifie ces
fonctions, reporter ses changements en gardant ces paramètres. Les conditions élargies
(`supportsSwitch || isLight`) dans `DetailScreen`, `DetailViewModel`, `DashboardViewModel` et
`NotificationWorker`, ainsi que la logique du bouton mural des relais (`disablesPlanningToday`) dans
`DashboardViewModel`, sont aussi des changements du fork.

## 2. Numérotation

- Tag : `fork/<versionName amont>-fork.<N>`, p. ex. `fork/2.16.1-fork.3`.
- `N` est un compteur unique du fork, **jamais remis à zéro** (même quand l'amont change de
  version) : `versionCode = 1000 + N` croît toujours, donc chaque APK s'installe par-dessus le
  précédent.
- `versionName` de l'APK : `<versionName amont>-fork.<N>`.
- Dernier `N` utilisé : `git tag -l 'fork/*' --sort=-creatordate | head -1`.
- `defaultConfig.versionCode`/`versionName` (lus par F-Droid côté amont) ne sont jamais modifiés.
- Un build release sans `-PforkBuild=N` échoue volontairement.

## 3. Publier

Avant de tagger, ajouter une section `## <x.y.z>-fork.<N>` (même titre, pour les testeurs) aux trois
journaux `docs/fork/CHANGELOG.md` (anglais), `CHANGELOG.fr.md` et `CHANGELOG.ru.md`, et les committer sur
`fork/main` : les notes de la Release publient la section anglaise (à défaut, la liste brute des
commits), puis les sections française et russe en blocs repliables (omises si la section traduite manque).

```bash
git switch fork/main && git pull
git tag -a fork/<x.y.z>-fork.<N> -m "<résumé d'une ligne en anglais pour les testeurs>"
git push origin fork/main fork/<x.y.z>-fork.<N>
```

Le workflow `.github/workflows/fork-release.yml` démarre sur ce tag (et uniquement sur un tag
`fork/*`) : contrôle du tag (`scripts/fork/check-tag.sh`), tests unitaires, build release signé,
puis Release GitHub avec l'APK, son `.sha256` et les notes (`scripts/fork/release-notes.sh`).
Suivre avec `gh run watch` (ou l'onglet *Actions*), puis vérifier
<https://github.com/mezinster/Hestia/releases/latest>.

Essai sans publier : *Actions → Fork release → Run workflow* (ou
`gh workflow run fork-release.yml --ref fork/main -f tag=fork/<x.y.z>-fork.<N>`) reconstruit un tag
existant (refusé s'il n'est pas dans `fork/main`) et crée la Release **toujours en brouillon** ; la
publier ensuite dans l'interface ou avec `gh release edit <tag> --draft=false`. La supprimer ensuite (`gh release delete <tag> --yes`)
si ce n'était qu'un essai ; si une Release existe déjà pour ce tag, le workflow échoue sans rien
écraser.

En cas d'échec : corriger sur `fork/main` et publier `N + 1` (un numéro perdu est sans conséquence ;
ne jamais réutiliser un tag déjà poussé).

## 4. Secrets GitHub

Environnement `fork-release` (*Settings → Environments*), règles de déploiement limitées aux tags
`fork/*` et à la branche `fork/main` (lancements manuels) : un workflow lancé depuis une autre
branche n'y a pas accès. Secrets d'environnement :

| Secret | Contenu |
|---|---|
| `FORK_KEYSTORE_B64` | `~/.android-keys/hestia-fork-test.jks` encodé en base64 (une ligne) |
| `FORK_STORE_PASSWORD` | valeur de `HESTIA_FORK_STORE_PASSWORD` |
| `FORK_KEY_ALIAS` | valeur de `HESTIA_FORK_KEY_ALIAS` |
| `FORK_KEY_PASSWORD` | valeur de `HESTIA_FORK_KEY_PASSWORD` |

La publication utilise le jeton éphémère du workflow (`github.token`, `contents: write` sur le
seul job `publish`, qui n'a pas accès aux secrets de signature) : aucun jeton personnel.

Déclarer les secrets **sans les afficher** (`gh secret set` lit l'entrée standard) :

```bash
E=(--repo mezinster/Hestia --env fork-release)
base64 -w0 ~/.android-keys/hestia-fork-test.jks | gh secret set FORK_KEYSTORE_B64 "${E[@]}"
for p in STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  printf '%s' "$(sed -n "s/^HESTIA_FORK_${p}=//p" ~/.gradle/gradle.properties)" |
    gh secret set "FORK_${p}" "${E[@]}"
done
gh secret list "${E[@]}"
```

Ne jamais afficher ces valeurs dans un terminal partagé, un journal ou une conversation. Les étapes
du workflow ne les affichent pas (pas de `set -x`).

## 4bis. Miroir Codeberg

`.github/workflows/codeberg-mirror.yml` pousse toutes les branches et tous les tags vers
<https://codeberg.org/mezinster/Hestia> à chaque push sur `fork/main`, toutes les 6 h (rattrape les
autres branches et les tags), ou à la demande (`gh workflow run codeberg-mirror.yml --ref fork/main`).
Ce qui est supprimé sur GitHub l'est aussi sur Codeberg.

- Clé SSH **dédiée** (`~/.ssh/codeberg-mirror-hestia`, ed25519, sans phrase de passe) : clé
  privée dans le secret `CODEBERG_MIRROR_SSH_KEY` de l'environnement `codeberg-mirror` (règle de
  déploiement : branche `fork/main` uniquement), clé publique en *clé de déploiement
  avec écriture* sur Codeberg (*Settings → Deploy keys* du dépôt), jamais une clé de compte.
- Clés d'hôte de Codeberg dans la variable de dépôt `CODEBERG_KNOWN_HOSTS` (obtenues par
  `ssh-keyscan codeberg.org`, empreintes comparées à <https://docs.codeberg.org/security/ssh-fingerprint/>).
- Pour arrêter le miroir : désactiver le workflow (`gh workflow disable codeberg-mirror.yml`) et
  supprimer la clé de déploiement sur Codeberg.
- GitHub **suspend les workflows planifiés après 60 jours sans activité** dans un dépôt public : un
  push sur `fork/main` reste aussitôt mis en miroir, mais les autres branches, les tags et `main`
  (synchronisations amont) n'arriveraient plus sur Codeberg, sans autre avertissement qu'un e-mail de
  GitHub. Après une longue pause, vérifier *Actions → Codeberg mirror* : le réactiver si besoin
  (`gh workflow enable codeberg-mirror.yml`), puis le lancer une fois
  (`gh workflow run codeberg-mirror.yml --ref fork/main`).

## 5. Repli local (CI indisponible)

```bash
git tag -a fork/<x.y.z>-fork.<N> -m "<résumé>"
ANDROID_HOME=~/Android/Sdk CI_COMMIT_TAG=fork/<x.y.z>-fork.<N> scripts/fork/ci-build.sh
git push origin fork/main fork/<x.y.z>-fork.<N>   # lance aussi le workflow : l'annuler (gh run cancel) pour publier à la main
gh release create fork/<x.y.z>-fork.<N> build/fork-release/dist/* --repo mezinster/Hestia --verify-tag \
  --title "$(cat build/fork-release/title.txt)" --notes-file build/fork-release/notes.md
```

Si le workflow a déjà publié la Release, ne pas la recréer à la main (`gh release create`
échouerait).

La signature locale lit `HESTIA_FORK_*` dans `~/.gradle/gradle.properties`.

## 6. Base Room

Le fork utilise la version 20 de la base (variateurs `isLight` en v18, volets `isCover` en v19, événements de volet
en pause `paused_cover_events` en v20). Si l'amont publie à son tour une base v18, v19 ou v20 (autre contenu),
renuméroter les migrations du fork (v21 et suivantes, n'ajoutant `isLight` et `isCover` que si la colonne est absente,
et `paused_cover_events` avec `CREATE TABLE IF NOT EXISTS`) **avant** de publier une version fusionnée.

## 7. Tests des scripts

```bash
scripts/fork/test-check-tag.sh
scripts/fork/test-release-notes.sh
```
