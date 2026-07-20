# Backlog — Hestia

Points relevés en cours de route, à traiter dans un lot ultérieur (pas des bugs bloquants).

## Reporté au lot 7 (finitions)

- **Logo Hestia en tête d'application.** Aucun logo/branding dans la barre du haut
  actuellement. À poser en même temps que l'icône adaptative définitive (le placeholder
  actuel ferait « pas fini »). Relevé au lot 2 (2026-07-19).

- **Sélecteur « Perso » à rouleaux crantés.** La version actuelle est un simple double champ
  heures/minutes (fonctionnel). Le SPEC prévoit la métaphore « minuterie mécanique » avec deux
  rouleaux que l'on fait défiler au doigt + retour haptique. Implémentation maison (sans
  dépendance). Relevé au lot 3 (2026-07-19).

## À considérer (design/UX)

- **Distinguer l'extinction « fin de minuteur » de l'extinction manuelle** dans le journal.
  Actuellement les deux apparaissent « Éteint » (on ne peut pas être certain de la cause côté
  appareil). Une heuristique (minuteur actif au relevé précédent) pourrait afficher un libellé
  dédié. Relevé au lot 3.

## Relevé au lot 4 (présence)

- **Plage de présence de nuit** (fin < début, ex. 22:00 → 02:00) non gérée : la V1 exige
  fin > début le même jour (le script ne gère pas le passage de minuit). À ajouter si besoin.
- **Sélecteur d'heures de présence** : simples champs heures/minutes pour l'instant (comme le
  « Perso » du minuteur). À remplacer par un vrai sélecteur d'heure lors de la passe finitions.
- **Confirmation « modifications non enregistrées »** sur l'écran de config présence si l'on
  quitte après avoir changé les heures sans déployer (faibles enjeux, non bloquant).

## Relevé au lot 6 (widget)

- **Espace trop important au-dessus du titre** (« Hestia » sur le Tableau, « Réglages », et
  probablement « À propos ») : hauteur/padding de la `TopAppBar` à resserrer. Passe finitions.
- **Widget en 1×2 sur Android 11** (1×1 correct sur Android 17) : `targetCellWidth/Height`
  est ignoré avant API 31 ; affiner `minWidth/minHeight` pour tenir en 1×1 sur API 30.
- **Aperçu du widget** dans le sélecteur non stylé (pas de `previewImage`). Passe finitions.

## Fonctionnalités futures (post-V1)

- **Programmation d'une plage horaire future** (ex. « 2 h de charge de 15 h à 17 h » alors qu'il
  est 11 h). À faire via le composant **Schedule natif de Shelly** (cron), donc autonome sur
  l'appareil — fidèle au principe « pas de scheduler côté Android ». Nouvelle fonctionnalité,
  hors périmètre des lots V1. Relevé au lot 7.
- **Notifications** : fin de minuterie, début et fin de programmation. ⚠️ Hors V1 (CLAUDE.md :
  pas de notifications en V1 ; nécessite la permission notifications + un mécanisme de veille).
- **Vérification du firmware de la prise** (via `Shelly.CheckForUpdate`) + notif si mise à jour
  dispo. ⚠️ Nuance à trancher : cette méthode fait **contacter le serveur de Shelly par
  l'appareil** — à confronter au principe « 100 % local » avant de l'implémenter.
- **Audit des fonctions de la prise non gérées** par Hestia (recenser ce que l'API RPC expose
  et qu'on n'exploite pas encore : mesure d'énergie détaillée, planning, etc.).

## Idées à étudier (post-lot 3)

- **Bandeau d'information Android 17+** expliquant la demande d'autorisation réseau local
  **avant** que l'utilisateur n'appuie sur « + » pour ajouter un appareil (préparer le terrain
  avant le popup système). Relevé au lot 3 (2026-07-19).
- **Consommation en temps réel sur le Tableau** (puissance instantanée `apower`), pour les
  appareils avec mesure (`hasPowerMetering`). Relevé au lot 3.
- **Notification « charge terminée »** (bascule vers une consommation faible, ex. Mac ou
  scooter en charge). ⚠️ Hors périmètre V1 (CLAUDE.md : pas de notifications en V1 ; nécessite
  la permission notifications + un mécanisme de surveillance). À étudier pour une version
  ultérieure. Relevé au lot 3.
