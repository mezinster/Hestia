# Publier une version du fork (mezinster/Hestia)

Procédure du mainteneur du fork. Les testeurs ont leur propre guide : [TESTING.md](TESTING.md).

## 1. Branches

- `main` : miroir exact de `upstream/main` (kapoue/Hestia). Jamais de commit propre au fork.
- `fork/main` : branche d'intégration = amont + branches de fonctionnalité du fork + configuration
  de publication (bloc en fin de `app/build.gradle.kts`, `app/src/release/AndroidManifest.xml`,
  `scripts/fork/`, `.woodpecker/`, `docs/fork/`). **Les publications sont taguées uniquement ici.**
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

```bash
git switch fork/main && git pull
git tag -a fork/<x.y.z>-fork.<N> -m "<résumé d'une ligne en anglais pour les testeurs>"
git push origin fork/main fork/<x.y.z>-fork.<N>
```

Le pipeline `.woodpecker/release.yaml` démarre sur ce tag (et uniquement sur un tag `fork/*`) :
contrôle du tag (`scripts/fork/check-tag.sh`), tests unitaires, build release signé, puis Release
Codeberg avec l'APK, son `.sha256` et les notes (`scripts/fork/release-notes.sh`). Suivre sur
<https://ci.codeberg.org>, puis vérifier <https://codeberg.org/mezinster/Hestia/releases/latest>.

En cas d'échec : corriger sur `fork/main` et publier `N + 1` (un numéro perdu est sans conséquence ;
ne jamais réutiliser un tag déjà poussé).

## 4. Secrets Woodpecker

Une fois l'accès à ci.codeberg.org accordé et le dépôt `mezinster/Hestia` activé dans Woodpecker,
déclarer dans *Settings → Secrets* du dépôt :

| Secret | Contenu | Restriction |
|---|---|---|
| `fork_keystore_b64` | `~/.android-keys/hestia-fork-test.jks` encodé en base64 (une ligne) | événement `tag` |
| `fork_store_password` | valeur de `HESTIA_FORK_STORE_PASSWORD` | événement `tag` |
| `fork_key_alias` | valeur de `HESTIA_FORK_KEY_ALIAS` | événement `tag` |
| `fork_key_password` | valeur de `HESTIA_FORK_KEY_PASSWORD` | événement `tag` |
| `codeberg_release_token` | jeton Codeberg **dédié** (voir ci-dessous) | événement `tag` + image `woodpeckerci/plugin-release` uniquement |

Encoder la clé **sans l'afficher** :

```bash
(umask 077; base64 -w0 ~/.android-keys/hestia-fork-test.jks > /tmp/fork-key.b64)
# ouvrir /tmp/fork-key.b64 dans un éditeur, copier son contenu dans Woodpecker, puis :
shred -u /tmp/fork-key.b64
```

Jeton de publication : Codeberg → *Settings → Applications → Generate New Token*, nom
`woodpecker-release`, permissions **repository : Read and write** et **misc : Read** (exigé par
l'extension de publication sur Forgejo), tout le reste sur *No access*. Ne jamais réutiliser le
jeton personnel de `tea`.

Ne jamais afficher ces valeurs dans un terminal partagé, un journal ou une conversation. Les étapes
du pipeline ne les affichent pas (pas de `set -x`).

## 5. Repli local (CI indisponible)

```bash
git tag -a fork/<x.y.z>-fork.<N> -m "<résumé>"
ANDROID_HOME=~/Android/Sdk CI_COMMIT_TAG=fork/<x.y.z>-fork.<N> scripts/fork/ci-build.sh
git push origin fork/main fork/<x.y.z>-fork.<N>   # sans accès Woodpecker, aucun pipeline ne démarre
tea releases create --login codeberg --repo mezinster/Hestia --tag fork/<x.y.z>-fork.<N> \
  --title "$(cat build/fork-release/title.txt)" --note-file build/fork-release/notes.md
for f in build/fork-release/dist/*; do
  tea releases assets create --login codeberg --repo mezinster/Hestia fork/<x.y.z>-fork.<N> "$f"
done
```

`--asset` directement sur `tea releases create` (tea 0.16) échoue sans message et ne crée rien
(constaté sur `fork/2.16.1-fork.1`) : créer la Release d'abord, puis joindre les fichiers.

La signature locale lit `HESTIA_FORK_*` dans `~/.gradle/gradle.properties`.

Si Woodpecker **est** actif pour le dépôt, pousser le tag suffit : ne pas créer la Release à la main
en plus (le pipeline échouerait sur la Release existante).

## 6. Base Room

Le fork utilise la version 18 de la base (colonne `isLight`, variateurs). Si l'amont publie à son
tour une base v18 (autre contenu), renuméroter la migration du fork (v19, n'ajoutant `isLight` que
si la colonne est absente) **avant** de publier une version fusionnée.

## 7. Tests des scripts

```bash
scripts/fork/test-check-tag.sh
scripts/fork/test-release-notes.sh
```
