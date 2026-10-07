#!/usr/bin/env bash
# Tests de release-notes.sh dans un dépôt git jetable : ./scripts/fork/test-release-notes.sh
set -uo pipefail
script="$(cd "$(dirname "$0")" && pwd)/release-notes.sh"
repo="$(mktemp -d)"; trap 'rm -rf "$repo"' EXIT
cd "$repo" && git init -q && git config user.email t@t && git config user.name t
git commit -q --allow-empty -m "Commit ancien de l'amont"
git tag -a fork/2.16.1-fork.1 -m "First summary line"
git commit -q --allow-empty -m "Ajoute les variateurs"
git commit -q --allow-empty -m "Corrige le curseur"
git tag -a fork/2.16.1-fork.2 -m "Dimmers for testers"
fails=0
check() { grep -qF -- "$2" <<<"$1" || { echo "ÉCHEC : « $2 » absent ($3)"; fails=$((fails+1)); }; }
absent() { grep -qF -- "$2" <<<"$1" && { echo "ÉCHEC : « $2 » présent ($3)"; fails=$((fails+1)); }; }
n2="$("$script" fork/2.16.1-fork.2)"
check "$n2" "Unofficial test build" "en-tête"
check "$n2" "Allterco Robotics" "mention d'indépendance"
check "$n2" "Dimmers for testers" "message du tag"
check "$n2" "- Ajoute les variateurs" "commit depuis le tag précédent"
check "$n2" "- Corrige le curseur" "commit depuis le tag précédent"
absent "$n2" "Commit ancien de l'amont" "commit antérieur au tag précédent"
# Journal des modifications : la section du tag remplace la liste brute des commits.
mkdir -p docs/fork
printf '# Changelog\n\n## 2.16.1-fork.3\n\n### Shutters\n\n- Open, close and stop shutters\n\n## 2.16.1-fork.2\n\n- Old entry\n' > docs/fork/CHANGELOG.md
git add docs/fork/CHANGELOG.md && git commit -q -m "Ajoute le journal des modifications"
git tag -a fork/2.16.1-fork.3 -m "Shutters for testers"
n3="$("$script" fork/2.16.1-fork.3)"
check "$n3" "### Shutters" "section du journal"
check "$n3" "- Open, close and stop shutters" "entrée du journal"
absent "$n3" "Ajoute le journal des modifications" "pas de commits bruts quand le journal a une section"
absent "$n3" "Old entry" "section suivante exclue"
git commit -q --allow-empty -m "Corrige un détail"
git tag -a fork/2.16.1-fork.4 -m "No changelog section"
n4="$("$script" fork/2.16.1-fork.4)"
check "$n4" "- Corrige un détail" "repli sur les commits sans section du journal"
n1="$("$script" fork/2.16.1-fork.1)"
check "$n1" "First release of the new release pipeline" "premier tag"
absent "$n1" "Commit ancien de l'amont" "pas d'historique complet au premier tag"
[[ $fails -eq 0 ]] && echo "release-notes : OK" || { echo "release-notes : $fails échec(s)"; exit 1; }
