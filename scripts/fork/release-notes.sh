#!/usr/bin/env bash
# Notes de publication d'un tag du fork (Markdown sur stdout) : en-tête fixe pour les testeurs,
# résumé du tag annoté, puis titres des commits depuis le tag fork/* précédent ; enfin les mêmes
# notes en français et en russe (blocs repliables) quand les journaux traduits ont la section.
set -euo pipefail
tag="${1:?usage : release-notes.sh <tag>}"
prev="$(git tag -l 'fork/*' --sort=-creatordate --merged "$tag" | grep -vxF "$tag" | head -1 || true)"

cat <<'NOTES'
**Unofficial test build** of [Hestia](https://codeberg.org/kapoue/Hestia) with the features of this fork. Runs on Android 10 to 17. It installs **alongside** the official F-Droid version (separate app "Hestia (test)", separate settings).

How to install, update and report a bug: [tester guide](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.md) · [issues](https://github.com/mezinster/Hestia/issues)

> Independence: Hestia is an independent project, with no connection to Shelly or Allterco Robotics. It is neither commissioned, sponsored, nor endorsed by Shelly. Shelly is a registered trademark of its respective owner; it is mentioned only for technical compatibility.

NOTES
summary="$(git tag -l --format='%(contents)' "$tag" | sed '/^-----BEGIN/,$d')"
[[ -n "${summary//[[:space:]]/}" ]] && printf '## Summary\n\n%s\n\n' "$summary"
echo "## Changes"
echo
# Section « ## <version> » d'un journal docs/fork/CHANGELOG*.md tel qu'il est au tag (rédigé pour
# les testeurs) ; vide si le fichier ou la section n'existe pas.
version="${tag#fork/}"
section() {
    git show "$tag:docs/fork/$1" 2>/dev/null \
        | awk -v v="## $version" '$0 == v { on = 1; next } on && /^## / { exit } on' \
        | sed -e '/./,$!d' || true
}
changelog="$(section CHANGELOG.md)"
if [[ -n "${changelog//[[:space:]]/}" ]]; then
    printf '%s\n' "$changelog"
elif [[ -n "$prev" ]]; then
    git log --no-merges --format='- %s' "$prev..$tag"
else
    echo "First release of the new release pipeline (Russian translation, device-kind detection, dimmers)."
fi

# Versions française et russe, repliées, seulement si le journal traduit a une section pour ce tag.
translated() {   # <langue affichée> <journal> <en-tête> <titre « Changes »>
    local body
    body="$(section "$2")"
    [[ -n "${body//[[:space:]]/}" ]] || return 0
    printf '\n<details>\n<summary>%s</summary>\n\n%s\n\n## %s\n\n%s\n\n</details>\n' "$1" "$3" "$4" "$body"
}
translated "Français" CHANGELOG.fr.md "$(cat <<'NOTES'
**Version de test non officielle** de [Hestia](https://codeberg.org/kapoue/Hestia) avec les fonctionnalités de ce fork. Fonctionne d'Android 10 à 17. Elle s'installe **à côté** de la version officielle F-Droid (appli distincte « Hestia (test) », réglages séparés).

Installer, mettre à jour, signaler un bug : [guide du testeur](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.fr.md) · [issues](https://github.com/mezinster/Hestia/issues)

> Indépendance : Hestia est un projet indépendant, sans aucun lien avec Shelly ni avec Allterco Robotics. Ce projet n'est ni commandité, ni sponsorisé, ni approuvé par Shelly. Shelly est une marque déposée de son propriétaire respectif ; elle n'est citée qu'à titre de compatibilité technique.
NOTES
)" "Changements"
translated "Русский" CHANGELOG.ru.md "$(cat <<'NOTES'
**Неофициальная тестовая сборка** [Hestia](https://codeberg.org/kapoue/Hestia) с возможностями этого форка. Работает на Android с 10 по 17. Ставится **рядом** с официальной версией из F-Droid (отдельное приложение «Hestia (test)», отдельные настройки).

Как установить, обновить и сообщить об ошибке: [руководство тестировщика](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.ru.md) · [issues](https://github.com/mezinster/Hestia/issues)

> Независимость: Hestia — независимый проект, никак не связанный ни с Shelly, ни с Allterco Robotics. Проект не создавался по заказу Shelly, не спонсируется и не одобрен этой компанией. Shelly — зарегистрированный товарный знак соответствующего правообладателя; он упоминается исключительно для указания технической совместимости.
NOTES
)" "Изменения"
