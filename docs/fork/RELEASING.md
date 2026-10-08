# Publishing a fork release (mezinster/Hestia)

**English** · [Français](RELEASING.fr.md) · [Русский](RELEASING.ru.md)

Procedure for the fork maintainer. Testers have their own guide: [TESTING.md](TESTING.md).

## 1. Branches

Main repository: <https://github.com/mezinster/Hestia> (`origin`). <https://codeberg.org/mezinster/Hestia>
is a **read-only mirror** fed by `.github/workflows/codeberg-mirror.yml` (see § 4bis):
never push or merge anything there. Upstream remains `upstream` =
<https://codeberg.org/kapoue/Hestia> (read-only).

- `main`: exact mirror of `upstream/main` (kapoue/Hestia). Never a fork-specific commit.
- `fork/main`: integration branch = upstream + fork feature branches + release configuration
  (block at the end of `app/build.gradle.kts`, `app/src/release/AndroidManifest.xml`,
  `scripts/fork/`, `.github/`, `docs/fork/`). **Releases are tagged only here.**
- `feature/*`: one branch per feature, merged into `fork/main` once ready.

Syncing with upstream:

```bash
git fetch upstream
git switch main && git merge --ff-only upstream/main && git push origin main
git switch fork/main && git merge main
git merge feature/<new-feature>     # if applicable
./gradlew testDebugUnitTest
git push origin fork/main
```

The fork configuration only adds lines (it never modifies upstream lines): a sync should not
produce conflicts because of it.

Expected conflicts when syncing (dimmer schedules, batch C3): `ShellyRpcClient.scheduleCreate`
and `DeviceRepository.reconstructPlannings` have a generic signature (`control, channelId` instead of
`switchId`); `applyIfAlreadyActive`/`deletePlanning` call `setChannelAt`. If upstream changes these
functions, carry its changes over while keeping these parameters. The widened conditions
(`supportsSwitch || isLight`) in `DetailScreen`, `DetailViewModel`, `DashboardViewModel` and
`NotificationWorker`, as well as the relay wall-switch logic (`disablesPlanningToday`) in
`DashboardViewModel`, are also fork changes.

## 2. Numbering

- Tag: `fork/<upstream versionName>-fork.<N>`, e.g. `fork/2.16.1-fork.3`.
- `N` is a single fork-wide counter, **never reset** (even when upstream changes
  version): `versionCode = 1000 + N` always increases, so each APK installs over the
  previous one.
- APK `versionName`: `<upstream versionName>-fork.<N>`.
- Last `N` used: `git tag -l 'fork/*' --sort=-creatordate | head -1`.
- `defaultConfig.versionCode`/`versionName` (read by F-Droid on the upstream side) are never modified.
- A release build without `-PforkBuild=N` fails on purpose.

## 3. Publishing

Before tagging, add a `## <x.y.z>-fork.<N>` section (same heading, for testers) to the three
changelogs `docs/fork/CHANGELOG.md` (English), `CHANGELOG.fr.md` and `CHANGELOG.ru.md`, and commit them
on `fork/main`: the Release notes publish the English section (failing that, the raw list of commits),
followed by the French and Russian ones in collapsible blocks (omitted if the translated section is missing).

```bash
git switch fork/main && git pull
git tag -a fork/<x.y.z>-fork.<N> -m "<one-line English summary for testers>"
git push origin fork/main fork/<x.y.z>-fork.<N>
```

The `.github/workflows/fork-release.yml` workflow starts on this tag (and only on a
`fork/*` tag): tag check (`scripts/fork/check-tag.sh`), unit tests, signed release build,
then a GitHub Release with the APK, its `.sha256` and the notes (`scripts/fork/release-notes.sh`).
Follow it with `gh run watch` (or the *Actions* tab), then check
<https://github.com/mezinster/Hestia/releases/latest>.

Dry run without publishing: *Actions → Fork release → Run workflow* (or
`gh workflow run fork-release.yml --ref fork/main -f tag=fork/<x.y.z>-fork.<N>`) rebuilds an
existing tag (refused unless it is in `fork/main`) and creates the Release **always as a draft**;
then publish it in the web UI or with `gh release edit <tag> --draft=false`. Delete it afterwards (`gh release delete <tag> --yes`)
if it was only a trial; if a Release already exists for this tag, the workflow fails without
overwriting anything.

On failure: fix on `fork/main` and publish `N + 1` (a lost number does not matter;
never reuse a tag that has already been pushed).

## 4. GitHub secrets

`fork-release` environment (*Settings → Environments*), deployment rules limited to
`fork/*` tags and the `fork/main` branch (manual runs): a workflow started from another
branch has no access to it. Environment secrets:

| Secret | Content |
|---|---|
| `FORK_KEYSTORE_B64` | `~/.android-keys/hestia-fork-test.jks` base64-encoded (one line) |
| `FORK_STORE_PASSWORD` | value of `HESTIA_FORK_STORE_PASSWORD` |
| `FORK_KEY_ALIAS` | value of `HESTIA_FORK_KEY_ALIAS` |
| `FORK_KEY_PASSWORD` | value of `HESTIA_FORK_KEY_PASSWORD` |

Publishing uses the workflow's ephemeral token (`github.token`, `contents: write` on the
`publish` job only, which has no access to the signing secrets): no personal token.

Set the secrets **without displaying them** (`gh secret set` reads standard input):

```bash
E=(--repo mezinster/Hestia --env fork-release)
base64 -w0 ~/.android-keys/hestia-fork-test.jks | gh secret set FORK_KEYSTORE_B64 "${E[@]}"
for p in STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  printf '%s' "$(sed -n "s/^HESTIA_FORK_${p}=//p" ~/.gradle/gradle.properties)" |
    gh secret set "FORK_${p}" "${E[@]}"
done
gh secret list "${E[@]}"
```

Never display these values in a shared terminal, a log or a conversation. The workflow
steps do not display them (no `set -x`).

## 4bis. Codeberg mirror

`.github/workflows/codeberg-mirror.yml` pushes all branches and all tags to
<https://codeberg.org/mezinster/Hestia> on every push to `fork/main`, every 6 h (catches up on
other branches and tags), or on demand (`gh workflow run codeberg-mirror.yml --ref fork/main`).
Whatever is deleted on GitHub is deleted on Codeberg too.

- **Dedicated** SSH key (`~/.ssh/codeberg-mirror-hestia`, ed25519, no passphrase): private
  key in the `CODEBERG_MIRROR_SSH_KEY` secret of the `codeberg-mirror` environment (deployment
  rule: `fork/main` branch only), public key as a *deploy key
  with write access* on Codeberg (repository *Settings → Deploy keys*), never an account key.
- Codeberg host keys in the `CODEBERG_KNOWN_HOSTS` repository variable (obtained with
  `ssh-keyscan codeberg.org`, fingerprints compared with <https://docs.codeberg.org/security/ssh-fingerprint/>).
- To stop the mirror: disable the workflow (`gh workflow disable codeberg-mirror.yml`) and
  delete the deploy key on Codeberg.

## 5. Local fallback (CI unavailable)

```bash
git tag -a fork/<x.y.z>-fork.<N> -m "<summary>"
ANDROID_HOME=~/Android/Sdk CI_COMMIT_TAG=fork/<x.y.z>-fork.<N> scripts/fork/ci-build.sh
git push origin fork/main fork/<x.y.z>-fork.<N>   # also starts the workflow: cancel it (gh run cancel) to publish by hand
gh release create fork/<x.y.z>-fork.<N> build/fork-release/dist/* --repo mezinster/Hestia --verify-tag \
  --title "$(cat build/fork-release/title.txt)" --notes-file build/fork-release/notes.md
```

If the workflow has already published the Release, do not recreate it by hand (`gh release create`
would fail).

Local signing reads `HESTIA_FORK_*` from `~/.gradle/gradle.properties`.

## 6. Room database

The fork uses database version 20 (dimmers `isLight` in v18, shutters `isCover` in v19, paused
shutter events `paused_cover_events` in v20). If upstream in turn publishes a v18, v19 or v20 database (different content),
renumber the fork's migrations (v21 and later, adding `isLight` and `isCover` only if the column is missing,
and `paused_cover_events` with `CREATE TABLE IF NOT EXISTS`) **before** publishing a merged release.

## 7. Script tests

```bash
scripts/fork/test-check-tag.sh
scripts/fork/test-release-notes.sh
```
