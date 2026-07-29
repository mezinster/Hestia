# Hestia

**Pilotage local d'appareils domotiques Shelly — sans cloud, sans compte, sans traçage.**

Hestia est une application Android qui pilote vos appareils Shelly (Gen2 et ultérieur)
directement sur votre réseau local, en HTTP, via leur API RPC embarquée. Aucune donnée ne
quitte votre réseau, par défaut.

## Fonctionnalités

- **Tableau** des canaux : état, interrupteur, en un coup d'œil.
- **Minuteur « marche pour X »** exécuté par l'appareil en autonomie (le téléphone peut être
  fermé ou éteint).
- **Simulation de présence** : un script généré par l'application s'exécute sur l'appareil et
  allume/éteint dans une plage horaire, avec marge aléatoire.
- **Planning** (récurrent ou occasion unique), avec coupure optionnelle sur seuil de
  consommation.
- **Sauvegarde / restauration** de la configuration au format JSON.
- **Journal de diagnostic** local, partageable pour le support.
- **Notifications instantanées optionnelles via ntfy** (désactivées par défaut) : voir
  ci-dessous.

## Vie privée

- Communication **uniquement** avec les adresses que vous saisissez vous-même.
- Aucune découverte réseau, aucun traçage, aucune bibliothèque d'analytics.
- Deux permissions seulement : `INTERNET` et `ACCESS_LOCAL_NETWORK` (Android 17+).
- Une seule exception, désactivée par défaut et activable à tout moment dans les Réglages :
  les **notifications instantanées via ntfy**, un service tiers. Si vous l'activez, ce sont vos
  appareils qui lui envoient directement le texte de vos notifications (aucun autre usage,
  rien n'est envoyé si vous ne l'activez pas).

## Compilation

```bash
./gradlew assembleDebug     # APK de debug
./gradlew testDebugUnitTest # tests unitaires
```

- `minSdk` 30 (Android 11), `targetSdk`/`compileSdk` 37 (Android 17), JDK 17.
- Build reproductible, sans dépendance propriétaire ni service Google.

## Langue

Application disponible en **français** et en **anglais**, adaptée à la langue du système
(anglais par défaut, français si l'appareil est en français).

## Licence

Distribué sous licence **GPLv3**. Voir [LICENSE](LICENSE) ou
<https://www.gnu.org/licenses/gpl-3.0.html>.

## Indépendance

Hestia est un projet indépendant, sans aucun lien avec Shelly ni avec Allterco Robotics. Ce
projet n'est ni commandité, ni sponsorisé, ni approuvé par Shelly. Aucune rémunération ni aucun
matériel n'a été fourni ou prêté par la marque. Shelly est une marque déposée de son
propriétaire respectif ; elle n'est citée qu'à titre de compatibilité technique.
