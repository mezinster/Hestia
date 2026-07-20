# Hestia — Checklist de validation manuelle

À dérouler sur les deux appareils avant chaque publication.

**Matériel**
- Sony sous Android 11 (API 30) — cible principale
- Pixel 10 Pro sous Android 17 (API 37) — validation de la permission réseau local
- Shelly Plug M Gen3, IP `192.168.1.96`, sans authentification

Marquer chaque ligne OK / KO / N/A, et noter l'appareil concerné en cas d'écart.

---

## 1. Premier lancement

- [ ] L'application s'ouvre **sans aucun popup de permission**
- [ ] Les trois onglets sont navigables
- [ ] L'écran Tableau affiche l'état vide avec l'invitation à ajouter un appareil
- [ ] L'écran À propos est complet : version, licence, QR code, mentions
- [ ] Le QR code fonctionne : le scanner avec un autre téléphone renvoie bien vers le dépôt

## 2. Permission réseau local (Pixel / Android 17 en priorité)

- [ ] L'écran d'explication apparaît **avant** la demande système, au premier contact réseau
- [ ] Refuser la permission : l'application reste navigable, aucun plantage, aucun écran bloquant
- [ ] Les tuiles affichent « Permission requise » avec un bouton de relance
- [ ] Le bouton de relance ouvre bien la demande, ou la page système si le refus est définitif
- [ ] Les Réglages affichent l'état correct de la permission
- [ ] Accorder la permission : les tuiles repassent en état normal sans redémarrer l'application
- [ ] Révoquer la permission depuis les réglages système pendant que l'application tourne :
      retour au premier plan, comportement correct

## 3. Ajout d'un appareil

- [ ] Adresse IP invalide rejetée avec un message clair (`192.168.1`, `abc`, champ vide)
- [ ] IP valide mais aucun appareil : message d'erreur explicite, pas de plantage
- [ ] IP d'un appareil non-Shelly (ex. la box) : rejet propre
- [ ] Ajout de la Plug M : succès, modèle correctement rapporté
- [ ] Le nombre de canaux détectés est correct (1 pour la Plug M)
- [ ] Doublon (même IP + même canal) : refusé avec message
- [ ] Modification du nom : prise en compte immédiate sur le Tableau
- [ ] Suppression : confirmation demandée, tuile retirée
- [ ] Réordonnancement conservé après redémarrage de l'application

## 4. Tableau et pilotage

- [ ] La tuile reflète l'état réel de la prise au lancement
- [ ] Allumer depuis l'application : la prise réagit, la LED et le libellé changent
- [ ] Éteindre depuis l'application : idem
- [ ] Allumer depuis l'interface web de la prise (`http://192.168.1.96`) :
      l'application reflète le changement en moins de 10 s
- [ ] Pull-to-refresh : rafraîchissement immédiat
- [ ] Le polling s'arrête en arrière-plan (vérifier l'absence de requêtes, ou la batterie)
- [ ] Le polling reprend au retour au premier plan
- [ ] Débrancher la prise : passage en « Hors ligne » avec bouton Réessayer
- [ ] Le bouton Réessayer fonctionne une fois la prise rebranchée
- [ ] Avec deux appareils dont un hors ligne : l'autre reste pleinement pilotable
- [ ] Couper le Wi-Fi du téléphone : message clair, pas de plantage
- [ ] Bouton d'ouverture de l'interface web : le navigateur s'ouvre sur la bonne adresse

## 5. Minuteur

- [ ] `1h` : la prise s'allume, le compte à rebours démarre et décroît
- [ ] Le compte à rebours reste cohérent après rotation de l'écran
- [ ] Le compte à rebours reste cohérent après mise en arrière-plan puis retour
- [ ] **Fermer complètement l'application** : la prise se coupe bien à l'échéance
- [ ] **Éteindre le téléphone** pendant un minuteur : la prise se coupe bien à l'échéance
- [ ] Le compte à rebours est correct après réouverture de l'application
- [ ] Annulation d'un minuteur en cours : `auto_off` bien désactivé côté appareil
      (vérifier dans l'interface web de la prise)
- [ ] Rouleaux « Perso » : sélection fluide, valeurs `0-23` et `0-59`
- [ ] Durée de 0 minute refusée
- [ ] Durée longue (ex. 12 h) acceptée et correcte
- [ ] Le journal d'activité enregistre bien les démarrages et annulations

## 6. Simulation de présence

- [ ] Déploiement du script : succès confirmé après relecture de l'état réel
- [ ] Le script apparaît sous le nom `hestia_presence` dans l'interface web de la prise
- [ ] Un script portant un autre nom n'est ni modifié ni supprimé
- [ ] Désinstaller puis réinstaller l'application, réajouter l'appareil :
      le script existant est bien détecté et son état correctement affiché
- [ ] Arrêt du script depuis l'application : effectif côté appareil
- [ ] Redémarrage de la prise (débrancher / rebrancher) : le script repart automatiquement
- [ ] Lancer un minuteur alors que le script est actif : le dialogue d'avertissement apparaît
- [ ] Les trois issues du dialogue se comportent comme prévu
- [ ] Dérive d'horloge : l'avertissement apparaît si l'écart dépasse 2 minutes
      (testable en coupant l'accès réseau de la prise un temps prolongé)

## 7. Sauvegarde

- [ ] Export : fichier créé, JSON lisible et complet
- [ ] Le dialogue d'avertissement apparaît avant tout import
- [ ] « Annuler » n'écrase rien
- [ ] Import du même fichier : état identique restitué
- [ ] Import alors que des appareils différents existent : ils sont bien tous remplacés
- [ ] Import d'un fichier corrompu ou d'un JSON quelconque : refus propre avec message
- [ ] Import sur une installation vierge : état complet restitué
- [ ] Le journal d'activité n'est pas présent dans le fichier exporté

## 8. Journal de diagnostic

- [ ] Cinq appuis sur le logo ouvrent l'écran de journal
- [ ] Moins de cinq appuis n'ouvrent rien, et aucun retour visuel n'apparaît pendant la séquence
- [ ] Appuis espacés de plus de 2 secondes : le compteur est réinitialisé, rien ne s'ouvre
- [ ] L'en-tête contient version de l'application, version d'Android, modèle, état de la
      permission, nombre d'appareils
- [ ] Les changements d'écran sont journalisés
- [ ] Un appui sur un interrupteur est journalisé, avec le résultat de l'appel RPC
- [ ] Une erreur réseau (prise débranchée) est journalisée avec un contexte exploitable
- [ ] L'avertissement sur les adresses IP est affiché avant partage
- [ ] Le partage ouvre bien la feuille système et le contenu arrive intact
      (tester vers un mail et vers une messagerie)
- [ ] Utilisation intensive prolongée : le nombre d'entrées plafonne, les plus anciennes
      sont supprimées, la taille de l'application ne dérive pas
- [ ] Aucun ralentissement perceptible de l'interface avec le journal actif

## 9. Widget

- [ ] Ajout du widget depuis l'écran d'accueil : l'écran de choix du canal apparaît
- [ ] Le widget affiche le bon nom et le bon état au moment de l'ajout
- [ ] Appui sur l'interrupteur : la prise bascule, le widget se met à jour
- [ ] Appui sur le titre : l'application s'ouvre sur le bon écran de détail
- [ ] Prise débranchée : le widget affiche l'état hors ligne, aucun plantage
- [ ] **Permission révoquée : l'appui ouvre l'application au lieu d'échouer silencieusement**
- [ ] **Appui alors que l'application n'a jamais été ouverte depuis le démarrage du téléphone**
- [ ] Suppression de l'appareil dans l'application : le widget affiche « Appareil supprimé »
- [ ] Plusieurs widgets pour des canaux différents : chacun pilote bien le sien
- [ ] Thème clair et thème sombre corrects sur le widget
- [ ] Redémarrage du téléphone : le widget reste fonctionnel
- [ ] Les actions du widget apparaissent dans le journal avec l'origine `widget`

## 10. Thèmes et affichage

- [ ] Thème clair : tous les textes lisibles, aucun contraste douteux
- [ ] Thème sombre : idem
- [ ] Bascule du thème système pendant l'utilisation : pas de plantage, rendu correct
- [ ] Chaque état est accompagné d'un libellé texte, jamais de la seule couleur
- [ ] Grande police système : aucun texte tronqué ni chevauchement
- [ ] Rotation de l'écran sur tous les écrans : pas de perte d'état
- [ ] Écran de petite taille (Sony) : grille lisible, boutons atteignables au pouce

## 11. Navigation

- [ ] Bouton retour système cohérent sur tous les écrans
- [ ] Quitter un écran avec des modifications non enregistrées : dialogue de confirmation
- [ ] « Abandonner » quitte sans enregistrer, « Continuer » revient à l'édition
- [ ] Aucun écran ne peut être atteint sans possibilité de retour

## 12. Avant publication

- [ ] `./gradlew lint` sans avertissement bloquant
- [ ] `./gradlew testDebugUnitTest` au vert
- [ ] Le manifeste ne déclare que `INTERNET` et `ACCESS_LOCAL_NETWORK`
- [ ] Recherche dans le code : aucune URL en dur autre que les adresses saisies
- [ ] `versionName` et `versionCode` incrémentés, aucun 13 dans le `versionName`
- [ ] Métadonnées fastlane à jour (description, captures, bannière)
- [ ] Mention d'indépendance présente dans l'À propos, le README et la fiche F-Droid
- [ ] Aucun mot de passe ni donnée sensible dans le journal de diagnostic
- [ ] Installation propre de l'APK release sur les deux appareils, parcours complet refait
