# Journal des modifications — versions de test du fork Hestia

[English](CHANGELOG.md) · **Français** · [Русский](CHANGELOG.ru.md)

Les changements de chaque version de test non officielle de ce fork, rédigés pour les testeurs. Les
notes de Release d'un tag `fork/<version>` publient la section du même nom (`## <version>`) ; à
défaut, elles reprennent la liste des commits.

## 2.18.0-fork.3

S'installe par-dessus 2.16.1-fork.2 (même appli « Hestia (test) », tes appareils et réglages sont
conservés). Met le fork au niveau des fonctionnalités de Hestia 2.18.0 (amont), réécrites pour le
fork et étendues aux variateurs et aux volets quand ça a du sens.

### Appareils qui exigent HTTPS (nouveau)

- **Les appareils Shelly récents qui refusent le HTTP en clair** (firmware 2.0 et plus, sortis
  d'usine) peuvent maintenant être ajoutés. Hestia le détecte, affiche l'empreinte du certificat de
  l'appareil et te demande de lui faire confiance une fois ; chaque échange vérifie ensuite que c'est
  toujours le même certificat.
- **Si le certificat change**, Hestia cesse de parler à l'appareil et sa tuile affiche « Certificat
  changé ». Modifier l'appareil → **Connexion chiffrée (HTTPS)** montre le certificat et permet de
  l'oublier.

### Appareils protégés par mot de passe (nouveau)

- **L'ajout d'un appareil protégé** demande son mot de passe. Il est stocké chiffré sur le
  téléphone, par appareil, et n'entre jamais dans les sauvegardes ni dans le journal de diagnostic.
- Modifier l'appareil → **Mot de passe de l'appareil** : le changer ou le retirer. La tuile affiche
  « Mot de passe requis » ou « Mot de passe refusé » en cas de problème.

### Détecteurs de fumée et appareils protégés

- Une alarme de fumée appelle les autres appareils en HTTP clair, sans mot de passe. Un appareil
  qui exige un mot de passe ou HTTPS **ne peut donc plus être choisi comme cible de coupure sur
  alarme ni comme relais ntfy** : il est grisé dans la liste avec la raison, et saisir son adresse à
  la main est refusé.
- Si un appareil déjà dans la liste de coupure d'un détecteur devient protégé, la tuile du
  détecteur affiche **« Coupure sur alarme à revoir »** jusqu'à ce que tu le décoches.
- Un détecteur de fumée protégé par mot de passe remonte toujours son alarme et coupe toujours les
  appareils ouverts que tu as choisis.

### Notifications choisies par appareil (nouveau)

- Modifier l'appareil → **Notifications** : un interrupteur par type.
  - prises et canaux de multiprise : début de planning, fin de planning, coupure sur seuil, fin de
    minuteur, simulation de présence ;
  - variateurs : début et fin de planning ;
  - volets : mouvements programmés ;
  - détecteurs de fumée : aucun, leurs alertes sont toujours notifiées.
- **Appliqué par l'appareil lui-même** (ça marche téléphone éteint) et aux notifications locales.
  Couper une notification ne change jamais ce que fait l'appareil : coupures, minuteurs et plannings
  continuent de fonctionner.
- Un planning avec seuil en cours à ce moment-là est mis à jour au prochain lancement de l'appli
  (une note dans Modifier l'appareil le signale).

### Autres améliorations

- Notification de coupure sur seuil : la durée de charge passe en tête.
- Les plannings avec seuil créés par d'anciennes versions sont **mis à niveau automatiquement** au
  lancement, jamais pendant qu'ils tournent.
- Réglages : un appareil multi-canaux (multiprise, variateur multi-lumières) et ses canaux
  s'affichent maintenant dans **une seule carte**.

### Corrections

- Plannings uniques (à une date) : l'année est lue correctement, une date lointaine n'est plus prise
  pour une date passée, et un planning échu n'est supprimé qu'une fois.
- Le mode démo n'envoie plus rien à ses adresses fictives.
- Les plannings n'envoient plus de notification ntfy vide.

### Bon à savoir

- Les appareils en HTTPS, les mots de passe et les notifications par appareil **n'ont pas encore été
  testés sur du vrai matériel**. Si tu as un tel appareil, ton retour est le bienvenu — voir le
  [guide des testeurs](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.fr.md).
- La base locale de l'appli est mise à niveau au premier lancement. Revenir ensuite à
  2.16.1-fork.2 impose de désinstaller d'abord « Hestia (test) ».

## 2.16.1-fork.2

S'installe par-dessus 2.16.1-fork.1 (même appli « Hestia (test) », tes appareils et réglages sont
conservés).

### Volets roulants (nouveau)

- **Nouveau type d'appareil « Volet »** pour les appareils Shelly en mode volet (par ex. Plus 2PM /
  Pro 2PM configurés en volet, Pro Dual Cover). Une tuile par volet ; un appareil double volet
  ajoute les deux d'un coup.
- **Tuile :** boutons ▲ ■ ▼ (ouvrir, arrêter, fermer) et l'état toujours écrit en toutes lettres :
  « Ouvert », « Fermé », « Ouverture… 40 % », « Arrêté à 40 % », « Calibration… ».
- **Écran Détail :** grands boutons Ouvrir / Arrêter / Fermer, un **curseur de position (0–100 %)**
  une fois le volet calibré, un bouton **Calibrer** (avec confirmation de sécurité), et des
  messages de défaut lisibles (obstacle, surpuissance, interrupteur de sécurité…).
- **Plannings exécutés par le volet lui-même** (ils continuent de fonctionner appli fermée ou
  téléphone éteint) :
  - un événement = une heure + des jours de la semaine, ou une date unique → **Ouvrir**, **Fermer**
    ou **Aller à N %** (position uniquement sur les volets calibrés) ;
  - **« Ajouter une plage »** crée deux événements d'un coup : ouvrir le matin et fermer le soir, ou
    l'inverse ;
  - modifier, mettre en pause / réactiver (un événement en pause est retiré du volet et conservé
    dans Hestia), supprimer ; jusqu'à 10 événements par volet ; les doublons et les dates passées
    sont refusés avec un message clair ;
  - les plannings créés dans l'appli officielle Shelly apparaissent aussi dans Hestia ;
  - le **prochain événement** est affiché sur la tuile : « Fermé · ouvre à 07:30 », « Ouvert · ferme
    lun. 21:00 ».
- **Notifications** pour les événements planifiés : notifications locales (si activées), et ntfy
  (si activé — envoyé par le volet lui-même). Désactiver ntfy le retire des plannings du volet, y
  compris pour un volet hors ligne à ce moment-là, dès qu'il redevient joignable.
- **Mode démo :** deux volets de démo (« Living Room Shutter », calibré ; « Bedroom Shutter », non
  calibré) pour tout essayer sans matériel. Ajouter ou mettre en pause un événement en mode démo ne
  change rien.

### Variateurs

- **Minuteur :** éteint automatiquement un variateur après un délai, avec un compte à rebours sur la
  tuile.
- **Nom synchronisé avec l'appareil**, comme pour les prises et relais (renommer dans Hestia le
  renomme sur l'appareil et dans l'appli Shelly).
- **Variateurs multicanaux** (par ex. Pro Dimmer 2PM, ou un RGBW en mode 4 lumières) regroupés sur
  une seule ligne du Tableau, un cercle par canal.
- **Plannings exécutés par le variateur lui-même**, comme les plannings des prises : plages horaires
  marche/arrêt, planning en cours affiché dans le Détail, « désactivé aujourd'hui » quand tu
  l'éteins pendant une plage, notifications.
- **Mode démo :** un variateur de démo à 4 canaux (« Kitchen Lights »).

### Corrections

- Réglages → Langue : choisir de nouveau « Suivre la langue du système » affiche désormais la bonne
  sélection (Android 13+).

### Bon à savoir

- Les volets et les plannings des variateurs n'ont **pas encore été testés sur du vrai matériel**.
  Si tu en as un, ton retour est le bienvenu — voir le [guide du testeur](https://github.com/mezinster/Hestia/blob/fork/main/docs/fork/TESTING.fr.md).
- La base de données locale de l'appli est mise à niveau au premier lancement. Revenir ensuite à
  2.16.1-fork.1 oblige à désinstaller d'abord « Hestia (test) ».
- Le guide du testeur explique désormais quoi faire quand Play Protect ou Android bloque
  l'installation.

## 2.16.1-fork.1

Première version issue de la nouvelle chaîne de publication. Fonctionne d'Android 10 à 17.

- **Traduction russe** (l'appli est désormais disponible en anglais, français et russe).
- **Détection du type d'appareil** à l'ajout : l'appli vérifie ce que l'appareil expose réellement
  (relais, variateur…) au lieu de se fier au type choisi ; le type prise s'appelle désormais
  « Prise / relais ».
- **Variateurs (première version) :** marche/arrêt et luminosité depuis la tuile et l'écran Détail.
