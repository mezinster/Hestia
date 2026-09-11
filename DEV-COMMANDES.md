# Commandes de dev — Hestia

Aide-mémoire des commandes utilisées au fil des sessions (installation sans câble notamment).
Voir aussi `CLAUDE.md` § Compilation et tests pour les commandes de build/lint standard.

## Compiler et récupérer l'APK

```bash
mkdir -p /tmp/hestia-apk && cd /Users/david/www/Hestia && ./gradlew assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk /tmp/hestia-apk/hestia-$(date +%Y%m%d-%H%M%S).apk && cd /tmp/hestia-apk && ls -t *.apk | head -1
```

`mkdir -p` en tête : `/tmp` est vidé au redémarrage du Mac, sans lui la commande échoue avec
« cd: no such file or directory » (vécu le 2026-09-11).

## Installer sans câble (David ne branche jamais son téléphone au Mac)

Servir `/tmp/hestia-apk` en HTTP sur le réseau local, avec une page d'accueil qui met en avant
le dernier APK arrivé (nom réel conservé au téléchargement) plutôt que la liste brute de
`python3 -m http.server` :

```bash
cd /tmp/hestia-apk && python3 ~/.claude/scripts/serve-apk.py 8000 apk Hestia
```

Puis sur le téléphone : `http://192.168.1.140:8000/` (IP du Mac fixée par bail réservé côté box,
confirmée le 2026-07-26).

## Vider les vieux APK

```bash
rm -f /tmp/hestia-apk/*.apk
```

## Signer un APK release pour test (clé de debug, suffisant hors publication F-Droid)

```bash
~/Library/Android/sdk/build-tools/37.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out <sortie> <apk>
```
