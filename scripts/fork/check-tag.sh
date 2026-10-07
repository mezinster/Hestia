#!/usr/bin/env bash
# Vérifie un tag de publication du fork (fork/<x.y.z>-fork.<N>) et affiche N.
# <x.y.z> doit être le versionName amont de app/build.gradle.kts : un tag mal tapé échoue avant
# toute construction (voir docs/fork/RELEASING.md).
set -euo pipefail
tag="${1:?usage : check-tag.sh <tag> [build.gradle.kts]}"
gradle_file="${2:-app/build.gradle.kts}"
if [[ ! "$tag" =~ ^fork/([0-9]+\.[0-9]+\.[0-9]+)-fork\.([1-9][0-9]*)$ ]]; then
    echo "Tag invalide : $tag (attendu fork/<x.y.z>-fork.<N>, N ≥ 1)" >&2
    exit 1
fi
version="${BASH_REMATCH[1]}"
n="${BASH_REMATCH[2]}"
upstream="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"$/\1/p' "$gradle_file" | head -1)"
if [[ "$version" != "$upstream" ]]; then
    echo "Version du tag ($version) différente du versionName amont ($upstream)" >&2
    exit 1
fi
echo "$n"
