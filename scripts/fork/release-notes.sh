#!/usr/bin/env bash
# Notes de publication d'un tag du fork (Markdown sur stdout) : en-tête fixe pour les testeurs,
# résumé du tag annoté, puis titres des commits depuis le tag fork/* précédent.
set -euo pipefail
tag="${1:?usage : release-notes.sh <tag>}"
prev="$(git tag -l 'fork/*' --sort=-creatordate --merged "$tag" | grep -vxF "$tag" | head -1 || true)"

cat <<'NOTES'
**Unofficial test build** of [Hestia](https://codeberg.org/kapoue/Hestia) with the features of this fork. Runs on Android 10 to 17. It installs **alongside** the official F-Droid version (separate app "Hestia (test)", separate settings).

How to install, update and report a bug: [tester guide](https://codeberg.org/mezinster/Hestia/src/branch/fork/main/docs/fork/TESTING.md) · [issues](https://codeberg.org/mezinster/Hestia/issues)

> Independence: Hestia is an independent project, with no connection to Shelly or Allterco Robotics. It is neither commissioned, sponsored, nor endorsed by Shelly. Shelly is a registered trademark of its respective owner; it is mentioned only for technical compatibility.

NOTES
summary="$(git tag -l --format='%(contents)' "$tag" | sed '/^-----BEGIN/,$d')"
[[ -n "${summary//[[:space:]]/}" ]] && printf '## Summary\n\n%s\n\n' "$summary"
echo "## Changes"
echo
# Section « ## <version> » du journal docs/fork/CHANGELOG.md tel qu'il est au tag (rédigé pour les
# testeurs) ; à défaut, titres bruts des commits.
version="${tag#fork/}"
changelog="$(git show "$tag:docs/fork/CHANGELOG.md" 2>/dev/null \
    | awk -v v="## $version" '$0 == v { on = 1; next } on && /^## / { exit } on' \
    | sed -e '/./,$!d' || true)"
if [[ -n "${changelog//[[:space:]]/}" ]]; then
    printf '%s\n' "$changelog"
elif [[ -n "$prev" ]]; then
    git log --no-merges --format='- %s' "$prev..$tag"
else
    echo "First release of the new release pipeline (Russian translation, device-kind detection, dimmers)."
fi
