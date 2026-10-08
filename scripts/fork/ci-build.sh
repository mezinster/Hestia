#!/usr/bin/env bash
# Construction d'une publication du fork, exécutée par GitHub Actions (.github/workflows/fork-release.yml) sur un
# tag fork/*. Utilisable en local pour tester (voir docs/fork/RELEASING.md). N'affiche jamais de secret.
set -euo pipefail
tag="${CI_COMMIT_TAG:?CI_COMMIT_TAG manquant}"
n="$(scripts/fork/check-tag.sh "$tag")"

# Outils Android épinglés et vérifiés (sauf si un SDK est déjà fourni, ex. en local).
if [[ -z "${ANDROID_HOME:-}" ]]; then
    export ANDROID_HOME="${FORK_SDK_DIR:-/opt/android-sdk}"   # FORK_SDK_DIR : essai local du chemin CI
    zip=commandlinetools-linux-15859902_latest.zip
    curl -fsSL -o "/tmp/$zip" "https://dl.google.com/android/repository/$zip"
    echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/$zip" | sha256sum -c -
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q "/tmp/$zip" -d "$ANDROID_HOME/cmdline-tools" && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    # `yes` meurt de SIGPIPE (141) dès que sdkmanager a fini : avec pipefail, cela ferait échouer
    # le script ; seul le code de sdkmanager compte.
    { yes || true; } | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" >/dev/null
fi

# Clé de signature : fichier temporaire, supprimé quoi qu'il arrive.
keystore="$(mktemp)"
trap 'rm -f "$keystore"' EXIT
if [[ -n "${FORK_KEYSTORE_B64:-}" ]]; then
    printf '%s' "$FORK_KEYSTORE_B64" | base64 -d > "$keystore"
    export HESTIA_FORK_STORE_FILE="$keystore"
fi

./gradlew --no-daemon testDebugUnitTest assembleRelease -PforkBuild="$n"

version="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"$/\1/p' app/build.gradle.kts | head -1)-fork.$n"
dist=build/fork-release/dist
mkdir -p "$dist"
cp app/build/outputs/apk/release/app-release.apk "$dist/hestia-$version.apk"
(cd "$dist" && sha256sum "hestia-$version.apk" > "hestia-$version.apk.sha256")
echo "Hestia $version (unofficial test build)" > build/fork-release/title.txt
scripts/fork/release-notes.sh "$tag" > build/fork-release/notes.md
echo "Publication prête : $dist/hestia-$version.apk"
