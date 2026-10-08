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
check "$n2" "https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.md" "guide testeur sur GitHub"
check "$n2" "https://github.com/mezinster/Hestia/issues" "issues sur GitHub"
check "$n2" "https://codeberg.org/kapoue/Hestia" "lien vers l'amont conservé"
absent "$n2" "codeberg.org/mezinster" "plus de lien vers le miroir Codeberg"
check "$n2" "Dimmers for testers" "message du tag"
check "$n2" "- Ajoute les variateurs" "commit depuis le tag précédent"
check "$n2" "- Corrige le curseur" "commit depuis le tag précédent"
absent "$n2" "Commit ancien de l'amont" "commit antérieur au tag précédent"
# Journal des modifications : la section du tag remplace la liste brute des commits.
mkdir -p docs/fork
printf '# Changelog\n\n## 2.16.1-fork.3\n\n### Shutters\n\n- Open, close and stop shutters\n\n## 2.16.1-fork.2\n\n- Old entry\n' > docs/fork/CHANGELOG.md
printf '# Journal\n\n## 2.16.1-fork.3\n\n### Volets\n\n- Ouvrir, fermer et arrêter les volets\n\n## 2.16.1-fork.2\n\n- Ancienne entrée\n' > docs/fork/CHANGELOG.fr.md
printf '# Журнал\n\n## 2.16.1-fork.3\n\n### Рольставни\n\n- Открыть, закрыть и остановить рольставни\n' > docs/fork/CHANGELOG.ru.md
git add docs/fork/CHANGELOG*.md && git commit -q -m "Ajoute le journal des modifications"
git tag -a fork/2.16.1-fork.3 -m "Shutters for testers"
n3="$("$script" fork/2.16.1-fork.3)"
check "$n3" "### Shutters" "section du journal"
check "$n3" "- Open, close and stop shutters" "entrée du journal"
absent "$n3" "Ajoute le journal des modifications" "pas de commits bruts quand le journal a une section"
absent "$n3" "Old entry" "section suivante exclue"
# Trilingue : blocs repliables français et russe, chacun avec son en-tête, son guide et sa section.
check "$n3" "<summary>Français</summary>" "bloc français"
check "$n3" "https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.fr.md" "guide testeur français"
check "$n3" "sans aucun lien avec Shelly ni avec Allterco Robotics" "indépendance en français"
check "$n3" "- Ouvrir, fermer et arrêter les volets" "entrée du journal français"
absent "$n3" "Ancienne entrée" "section française suivante exclue"
check "$n3" "<summary>Русский</summary>" "bloc russe"
check "$n3" "https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.ru.md" "guide testeur russe"
check "$n3" "никак не связанный ни с Shelly, ни с Allterco Robotics" "независимость по-русски"
check "$n3" "- Открыть, закрыть и остановить рольставни" "entrée du journal russe"
[[ "$(grep -c '^</details>$' <<<"$n3")" == 2 ]] || { echo "ÉCHEC : 2 blocs </details> attendus"; fails=$((fails+1)); }
git commit -q --allow-empty -m "Corrige un détail"
git tag -a fork/2.16.1-fork.4 -m "No changelog section"
n4="$("$script" fork/2.16.1-fork.4)"
check "$n4" "- Corrige un détail" "repli sur les commits sans section du journal"
absent "$n4" "<details>" "pas de bloc traduit sans section dans les journaux traduits"
n1="$("$script" fork/2.16.1-fork.1)"
check "$n1" "First release of the new release pipeline" "premier tag"
absent "$n1" "Commit ancien de l'amont" "pas d'historique complet au premier tag"
[[ $fails -eq 0 ]] && echo "release-notes : OK" || { echo "release-notes : $fails échec(s)"; exit 1; }
