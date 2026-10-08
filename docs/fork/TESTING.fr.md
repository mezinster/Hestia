# Hestia (test) — guide du testeur

[English](TESTING.md) · **Français** · [Русский](TESTING.ru.md)

## De quoi s'agit-il

Une **version de test non officielle** de [Hestia](https://codeberg.org/kapoue/Hestia), l'appli
Android libre qui pilote les appareils Shelly sur ton réseau local (sans cloud, sans compte). Ce fork
ajoute des fonctionnalités qui ne sont pas (encore) dans l'appli officielle :

- la traduction russe ;
- un message clair quand un appareil ne peut pas être piloté (volet, capteur, compteur d'énergie…) ;
- les variateurs (appareils `light`) : marche/arrêt et luminosité.

Elle fonctionne sur **Android 10 à 17**.

> Indépendance : Hestia est un projet indépendant, sans aucun lien avec Shelly ni avec Allterco
> Robotics. Ce projet n'est ni commandité, ni sponsorisé, ni approuvé par Shelly. Shelly est une
> marque déposée de son propriétaire respectif ; elle n'est citée qu'à titre de compatibilité technique.

## Installation

1. Ouvre <https://github.com/mezinster/Hestia/releases/latest> sur ton téléphone.
2. Dans **Assets**, télécharge `hestia-<version>.apk`.
3. Ouvre le fichier. Si Android le demande, autorise l'installation d'applis depuis ton navigateur ou
   ton gestionnaire de fichiers.

L'appli s'affiche sous le nom **« Hestia (test) »**. Elle s'installe **à côté** de la Hestia
officielle de F-Droid : les deux peuvent cohabiter sur le même téléphone, chacune avec ses propres
appareils et réglages.

## Mise à jour

Télécharge l'APK plus récent depuis le même lien et installe-le par-dessus l'ancien. Tes appareils et
tes réglages sont conservés. Il n'y a pas de mise à jour automatique : jette un œil au lien de temps
en temps, ou suis les releases du dépôt sur GitHub (Watch → Custom → Releases).

## Si l'installation est bloquée

- **« Application bloquée pour protéger votre appareil » (Google Play Protect) :** Play Protect
  avertit pour toute appli venant d'un développeur qu'il ne connaît pas encore — ça ne veut pas dire
  que l'appli pose problème. Touche **Plus de détails → Installer quand même**. Si cette option
  n'apparaît pas : Play Store → ta photo de profil → **Play Protect** → ⚙️ → désactive temporairement
  **Analyser les applications avec Play Protect**, installe, puis réactive-le.
- **« Application non installée » :**
  - vérifie que tu ouvres bien le fichier **le plus récent** (`hestia-<version>.apk` de la dernière
    release) : Android refuse d'installer une version *plus ancienne* par-dessus une plus récente ;
  - si ça échoue encore et que « Hestia (test) » est déjà installée, désinstalle-la (Paramètres →
    Applications) puis réinstalle-la — il faudra rajouter tes appareils ;
  - vérifie que le téléchargement est complet (environ 5 Mo) et qu'il reste de la place sur le
    téléphone.

La Hestia officielle de F-Droid n'est jamais touchée : c'est une appli distincte.

## Android 17

La première fois que Hestia contacte un appareil, Android demande l'autorisation d'accéder aux
**appareils de ton réseau local**. Hestia en a besoin pour parler à tes appareils Shelly ; elle ne
contacte que les adresses IP que tu as saisies.

## Vérifier le téléchargement (facultatif)

- Chaque APK est accompagné d'un fichier `.sha256` : `sha256sum -c hestia-<version>.apk.sha256`.
- L'APK est signé avec ce certificat (SHA-256), que tu peux vérifier avec une appli comme
  AppVerifier :

  ```
  17:69:2C:2F:A7:AF:58:D1:8E:90:54:B9:71:4E:B0:34:E3:86:68:B4:B5:B9:F5:45:80:4B:64:90:91:FA:DD:68
  ```

## Signaler un bug

Ouvre une issue sur <https://github.com/mezinster/Hestia/issues> en indiquant :

- ta version d'Android et ton modèle de téléphone ;
- le modèle de Shelly (et sa génération, si tu la connais) ;
- ce que tu as fait, ce que tu attendais, ce qui s'est passé ;
- le journal de diagnostic, si tu es d'accord pour le partager : sur le **Tableau**, touche le titre
  en haut **5 fois rapidement** pour ouvrir le **Journal de diagnostic**, puis **Partager**. Il ne
  contient aucun mot de passe, mais il contient tes **adresses IP locales et les noms de tes
  appareils**, et une issue est **publique**. Relis-le avant de le publier et retire ce que tu ne veux
  pas montrer, ou demande dans l'issue un autre moyen de l'envoyer.
