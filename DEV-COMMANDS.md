# Commandes de dev — Hestia

## Build + copie de l'APK (nom horodaté, pas de souci de cache)

```bash
cd /Users/david/www/Hestia && ./gradlew assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk /tmp/hestia-apk/hestia-$(date +%H%M%S).apk && cd /tmp/hestia-apk && ls -t *.apk | head -1
```

## Lancement du serveur (installation sans câble)

```bash
cd /tmp/hestia-apk && python3 -m http.server 8000
```

Puis sur le téléphone : `http://192.168.1.140:8000/<nom-affiché-par-la-commande-de-build>.apk`
(adresse IP du Mac à revérifier si elle a changé : `ipconfig getifaddr en0` ou `en1`).
