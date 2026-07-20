# Backlog — Hestia

Points relevés en cours de route, à traiter dans un lot ultérieur (pas des bugs bloquants).

## Finitions (petits polissages)

- **Sélecteur « Perso » à rouleaux crantés** (minuteur). La version actuelle est un simple
  double champ heures/minutes (fonctionnel). Le SPEC prévoit la métaphore « minuterie
  mécanique » avec deux rouleaux à faire défiler au doigt + retour haptique. Implémentation
  maison (sans dépendance).
- **Sélecteur d'heures de présence** : simples champs h/min pour l'instant → à passer en vrai
  sélecteur d'heure.
- **Confirmation « modifications non enregistrées »** sur l'écran de config présence si l'on
  quitte après avoir changé les heures sans déployer (faibles enjeux).
- **Distinguer l'extinction « fin de minuteur » de l'extinction manuelle** dans le journal
  (les deux apparaissent « Éteint »). Heuristique possible : minuteur actif au relevé précédent.

## Fonctionnalités futures (post-V1)

- **Programmation d'une plage horaire future** (ex. « 2 h de charge de 15 h à 17 h » alors qu'il
  est 11 h). À faire via le composant **Schedule natif de Shelly** (cron), donc autonome sur
  l'appareil — fidèle au principe « pas de scheduler côté Android ». Beau candidat pour un lot
  post-V1 dédié.
- **Notifications** : fin de minuterie, début/fin de programmation, « charge terminée » (bascule
  vers une consommation faible, ex. Mac ou scooter). ⚠️ Hors V1 (CLAUDE.md : pas de notifications
  en V1 ; nécessite la permission notifications + un mécanisme de veille).
- **Vérification du firmware de la prise** (via `Shelly.CheckForUpdate`) + notif si mise à jour
  dispo. ⚠️ Nuance à trancher : cette méthode fait **contacter le serveur de Shelly par
  l'appareil** — à confronter au principe « 100 % local » avant de l'implémenter.
- **Consommation en temps réel sur le Tableau** (`apower`), pour les appareils avec mesure
  (`hasPowerMetering`).
- **Audit des fonctions RPC de la prise non gérées** par Hestia (mesure d'énergie détaillée,
  planning, etc.).
- **Traduction anglaise** (déjà prévue au CLAUDE.md) : adaptation à la langue du système
  (anglais par défaut, français si l'appareil est en français).
