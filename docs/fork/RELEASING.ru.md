# Публикация версии форка (mezinster/Hestia)

[English](RELEASING.md) · [Français](RELEASING.fr.md) · **Русский**

Процедура для мейнтейнера форка. У тестировщиков своё руководство: [TESTING.ru.md](TESTING.ru.md).

## 1. Ветки

Основной репозиторий: <https://github.com/mezinster/Hestia> (`origin`). <https://codeberg.org/mezinster/Hestia>
— **зеркало только для чтения**, которое обновляет `.github/workflows/codeberg-mirror.yml` (см. § 4bis):
никогда ничего туда не пушь и не сливай. Апстрим по-прежнему `upstream` =
<https://codeberg.org/kapoue/Hestia> (только чтение).

- `main`: точное зеркало `upstream/main` (kapoue/Hestia). Никаких собственных коммитов форка.
- `fork/main`: интеграционная ветка = апстрим + ветки функций форка + конфигурация
  публикации (блок в конце `app/build.gradle.kts`, `app/src/release/AndroidManifest.xml`,
  `scripts/fork/`, `.github/`, `docs/fork/`). **Релизы тегируются только здесь.**
- `feature/*`: одна ветка на функцию, сливается в `fork/main`, когда готова.

Синхронизация с апстримом:

```bash
git fetch upstream
git switch main && git merge --ff-only upstream/main && git push origin main
git switch fork/main && git merge main
git merge feature/<новая-функция>     # при необходимости
./gradlew testDebugUnitTest
git push origin fork/main
```

Конфигурация форка только добавляет строки (никогда не изменяет строки апстрима): из-за неё
синхронизация не должна давать конфликтов.

Ожидаемые конфликты при синхронизации (расписания диммеров, пакет C3): `ShellyRpcClient.scheduleCreate`
и `DeviceRepository.reconstructPlannings` имеют обобщённую сигнатуру (`control, channelId` вместо
`switchId`); `applyIfAlreadyActive`/`deletePlanning` вызывают `setChannelAt`. Если апстрим меняет эти
функции, переноси его изменения, сохраняя эти параметры. Расширенные условия
(`supportsSwitch || isLight`) в `DetailScreen`, `DetailViewModel`, `DashboardViewModel` и
`NotificationWorker`, а также логика настенного выключателя реле (`disablesPlanningToday`) в
`DashboardViewModel` — тоже изменения форка.

## 2. Нумерация

- Тег: `fork/<versionName апстрима>-fork.<N>`, напр. `fork/2.16.1-fork.3`.
- `N` — единый счётчик форка, **никогда не сбрасывается** (даже когда апстрим меняет
  версию): `versionCode = 1000 + N` всегда растёт, поэтому каждый APK ставится поверх
  предыдущего.
- `versionName` APK: `<versionName апстрима>-fork.<N>`.
- Последний использованный `N`: `git tag -l 'fork/*' --sort=-creatordate | head -1`.
- `defaultConfig.versionCode`/`versionName` (их читает F-Droid на стороне апстрима) никогда не меняются.
- Release-сборка без `-PforkBuild=N` намеренно падает.

## 3. Публикация

Перед тегированием добавь раздел `## <x.y.z>-fork.<N>` (тот же заголовок, для тестировщиков) во все
три журнала — `docs/fork/CHANGELOG.md` (английский), `CHANGELOG.fr.md` и `CHANGELOG.ru.md` — и закоммить
их в `fork/main`: заметки Release публикуют английский раздел (если его нет — просто список
коммитов), а за ним французский и русский в сворачиваемых блоках (без них, если переведённого раздела нет).

```bash
git switch fork/main && git pull
git tag -a fork/<x.y.z>-fork.<N> -m "<однострочное описание на английском для тестировщиков>"
git push origin fork/main fork/<x.y.z>-fork.<N>
```

Workflow `.github/workflows/fork-release.yml` запускается по этому тегу (и только по тегу
`fork/*`): проверка тега (`scripts/fork/check-tag.sh`), unit-тесты, подписанная release-сборка,
затем GitHub Release с APK, его `.sha256` и заметками (`scripts/fork/release-notes.sh`).
Следи за ходом через `gh run watch` (или вкладку *Actions*), затем проверь
<https://github.com/mezinster/Hestia/releases/latest>.

Пробный прогон без публикации: *Actions → Fork release → Run workflow* (или
`gh workflow run fork-release.yml --ref fork/main -f tag=fork/<x.y.z>-fork.<N>`) пересобирает
существующий тег (отклоняется, если его нет в `fork/main`) и создаёт Release **всегда как черновик**;
затем опубликуй его в веб-интерфейсе или командой `gh release edit <tag> --draft=false`. Потом удали его (`gh release delete <tag> --yes`),
если это была лишь проба; если Release для этого тега уже существует, workflow падает, ничего
не перезаписывая.

При сбое: исправь в `fork/main` и опубликуй `N + 1` (потерянный номер ни на что не влияет;
никогда не переиспользуй уже запушенный тег).

## 4. Секреты GitHub

Окружение `fork-release` (*Settings → Environments*), правила развёртывания ограничены тегами
`fork/*` и веткой `fork/main` (ручные запуски): workflow, запущенный из другой
ветки, к нему доступа не имеет. Секреты окружения:

| Секрет | Содержимое |
|---|---|
| `FORK_KEYSTORE_B64` | `~/.android-keys/hestia-fork-test.jks` в base64 (одной строкой) |
| `FORK_STORE_PASSWORD` | значение `HESTIA_FORK_STORE_PASSWORD` |
| `FORK_KEY_ALIAS` | значение `HESTIA_FORK_KEY_ALIAS` |
| `FORK_KEY_PASSWORD` | значение `HESTIA_FORK_KEY_PASSWORD` |

Публикация использует эфемерный токен workflow (`github.token`, `contents: write` только для
job `publish`, у которого нет доступа к секретам подписи): никакого личного токена.

Задавай секреты, **не выводя их на экран** (`gh secret set` читает стандартный ввод):

```bash
E=(--repo mezinster/Hestia --env fork-release)
base64 -w0 ~/.android-keys/hestia-fork-test.jks | gh secret set FORK_KEYSTORE_B64 "${E[@]}"
for p in STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  printf '%s' "$(sed -n "s/^HESTIA_FORK_${p}=//p" ~/.gradle/gradle.properties)" |
    gh secret set "FORK_${p}" "${E[@]}"
done
gh secret list "${E[@]}"
```

Никогда не выводи эти значения в общем терминале, журнале или переписке. Шаги
workflow их не выводят (без `set -x`).

## 4bis. Зеркало Codeberg

`.github/workflows/codeberg-mirror.yml` пушит все ветки и все теги в
<https://codeberg.org/mezinster/Hestia> при каждом пуше в `fork/main`, каждые 6 ч (подхватывает
остальные ветки и теги) или по запросу (`gh workflow run codeberg-mirror.yml --ref fork/main`).
Всё, что удалено на GitHub, удаляется и на Codeberg.

- **Отдельный** SSH-ключ (`~/.ssh/codeberg-mirror-hestia`, ed25519, без парольной фразы): приватный
  ключ — в секрете `CODEBERG_MIRROR_SSH_KEY` окружения `codeberg-mirror` (правило
  развёртывания: только ветка `fork/main`), публичный ключ — как *deploy key
  с правом записи* на Codeberg (*Settings → Deploy keys* репозитория), никогда не ключ аккаунта.
- Ключи хоста Codeberg — в переменной репозитория `CODEBERG_KNOWN_HOSTS` (получены через
  `ssh-keyscan codeberg.org`, отпечатки сверены с <https://docs.codeberg.org/security/ssh-fingerprint/>).
- Чтобы остановить зеркало: отключи workflow (`gh workflow disable codeberg-mirror.yml`) и
  удали deploy key на Codeberg.
- GitHub **приостанавливает запланированные workflow после 60 дней без активности** в публичном
  репозитории: push в `fork/main` по-прежнему сразу попадает в зеркало, но остальные ветки, теги и
  `main` (синхронизация с апстримом) перестанут доходить до Codeberg — предупреждением будет только
  письмо от GitHub. После долгого перерыва проверь *Actions → Codeberg mirror*: при необходимости
  включи его снова (`gh workflow enable codeberg-mirror.yml`) и запусти один раз
  (`gh workflow run codeberg-mirror.yml --ref fork/main`).

## 5. Локальный запасной вариант (CI недоступен)

```bash
git tag -a fork/<x.y.z>-fork.<N> -m "<описание>"
ANDROID_HOME=~/Android/Sdk CI_COMMIT_TAG=fork/<x.y.z>-fork.<N> scripts/fork/ci-build.sh
git push origin fork/main fork/<x.y.z>-fork.<N>   # также запускает workflow: отмени его (gh run cancel), чтобы опубликовать вручную
gh release create fork/<x.y.z>-fork.<N> build/fork-release/dist/* --repo mezinster/Hestia --verify-tag \
  --title "$(cat build/fork-release/title.txt)" --notes-file build/fork-release/notes.md
```

Если workflow уже опубликовал Release, не создавай его заново вручную (`gh release create`
упадёт).

Локальная подпись читает `HESTIA_FORK_*` из `~/.gradle/gradle.properties`.

## 6. База Room

Форк использует версию базы 20 (диммеры `isLight` в v18, шторы `isCover` в v19, приостановленные
события штор `paused_cover_events` в v20). Если апстрим, в свою очередь, выпустит базу v18, v19 или v20 (с другим содержимым),
перенумеруй миграции форка (v21 и далее, добавляя `isLight` и `isCover`, только если столбца нет,
и `paused_cover_events` через `CREATE TABLE IF NOT EXISTS`) **до** публикации слитой версии.

## 7. Тесты скриптов

```bash
scripts/fork/test-check-tag.sh
scripts/fork/test-release-notes.sh
```
