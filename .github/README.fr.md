# Hestia — fork avec russe, variateurs et volets

[English](README.md) · **Français** · [Русский](README.ru.md)

**Pilotage local d'appareils domotiques Shelly sur Android — sans cloud, sans compte, sans traçage.**

Ceci est un **fork non officiel** de [Hestia](https://codeberg.org/kapoue/Hestia), de kapoue. Il
garde tout ce que fait l'application d'origine et y ajoute des fonctionnalités qui n'en font pas
(encore) partie. Il suit l'original de près et se synchronise régulièrement avec lui.

> Indépendance : Hestia est un projet indépendant, sans aucun lien avec Shelly ni avec Allterco
> Robotics. Ce projet n'est ni commandité, ni sponsorisé, ni approuvé par Shelly. Shelly est une
> marque déposée de son propriétaire respectif ; elle n'est citée qu'à titre de compatibilité technique.

## Ce qu'ajoute le fork

- **Traduction russe** : l'application est disponible en anglais, en français et en russe.
- Prise en charge d'**Android 10** (l'original demande Android 11).
- **Détection du type d'appareil** à l'ajout : Hestia vérifie ce que l'appareil expose réellement
  (relais, variateur, volet…) au lieu de se fier au type choisi.
- **Variateurs** : marche/arrêt et luminosité, minuteur, nom synchronisé avec l'appareil,
  variateurs multicanaux sur une seule ligne, et plannings exécutés par le variateur lui-même.
- **Volets roulants** : ouvrir / arrêter / fermer, curseur de position, calibration, messages de
  défaut lisibles, et plannings exécutés par le volet lui-même (ouvrir le matin, fermer le soir…).
- **Appareils de démonstration** pour tout ce qui précède, pour essayer l'application sans matériel.

Comme dans l'original, chaque planning et chaque minuteur est stocké et exécuté **par l'appareil
lui-même** : il continue de fonctionner application fermée ou téléphone éteint. Le détail par
version : [journal des modifications](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/CHANGELOG.fr.md).

## Installation

1. Ouvre la [dernière version](https://github.com/mezinster/Hestia/releases/latest) sur ton téléphone.
2. Dans **Assets**, télécharge `hestia-<version>.apk` et ouvre-le.

L'application s'appelle **« Hestia (test) »** et s'installe **à côté** de la Hestia officielle de
F-Droid, avec ses propres appareils et réglages. Pas de mise à jour automatique : suis les versions
du dépôt (*Watch → Custom → Releases*). Installation bloquée, mises à jour, signaler un bug : voir le
[guide du testeur](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.fr.md).

Les volets et les plannings des variateurs n'ont pas encore été testés sur du vrai matériel. Si tu
en as un, ton retour est le bienvenu dans les [issues](https://github.com/mezinster/Hestia/issues).

## Vie privée

Même engagement que l'original : Hestia ne communique **qu'avec** les adresses que tu saisis, sur
ton réseau local. Aucune découverte réseau, aucun analytics, aucun traçage. Deux permissions
seulement : `INTERNET` et `ACCESS_LOCAL_NETWORK` (Android 17+). Les rares fonctions qui peuvent
sortir sur Internet (notifications ntfy, repli via le Cloud Shelly, vérification du firmware) sont
**désactivées par défaut**, expliquées dans l'application et désactivables à tout moment. Voir le
[README d'origine](https://codeberg.org/kapoue/Hestia) pour la liste complète des fonctionnalités.

## Pour les développeurs

- `main` est le miroir du dépôt d'origine ; `fork/main` = original + fonctionnalités du fork
  (branche par défaut).
- Le code du fork vit autant que possible dans des fichiers neufs, pour que la synchronisation avec
  l'original reste simple.
- Les versions sont construites et signées par GitHub Actions à partir des tags `fork/*` :
  [procédure de publication](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/RELEASING.fr.md).
- Un miroir en lecture seule est tenu à jour sur [codeberg.org/mezinster/Hestia](https://codeberg.org/mezinster/Hestia).

## Licence

[GPLv3](https://github.com/mezinster/Hestia/blob/fork/main/LICENSE), comme l'original.
