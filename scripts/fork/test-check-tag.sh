#!/usr/bin/env bash
# Tests de check-tag.sh (sans CI) : ./scripts/fork/test-check-tag.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fixture="$(mktemp)"; trap 'rm -f "$fixture"' EXIT
printf '        versionCode = 55\n        versionName = "2.16.1"\n' > "$fixture"
fails=0
expect_ok() { out=$("$here/check-tag.sh" "$1" "$fixture" 2>/dev/null) && [[ "$out" == "$2" ]] || { echo "ÉCHEC : $1 → attendu $2, obtenu '${out:-<erreur>}'"; fails=$((fails+1)); }; }
expect_ko() { "$here/check-tag.sh" "$1" "$fixture" >/dev/null 2>&1 && { echo "ÉCHEC : $1 aurait dû être refusé"; fails=$((fails+1)); }; }
expect_ok "fork/2.16.1-fork.1" 1
expect_ok "fork/2.16.1-fork.42" 42
expect_ko "fork/2.16.1-fork.0"      # N commence à 1
expect_ko "fork/2.16.2-fork.3"      # version ≠ amont
expect_ko "v2.16.1-fork.3"          # préfixe
expect_ko "fork/2.16.1-test.1"      # ancien schéma
expect_ko "fork/2.16.1-fork.3x"
[[ $fails -eq 0 ]] && echo "check-tag : OK" || { echo "check-tag : $fails échec(s)"; exit 1; }
