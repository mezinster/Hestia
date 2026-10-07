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

- [ ] Les deux tuiles (Prise / Détecteur de fumée) s'affichent avant tout autre champ, rien
      d'autre visible avant ce choix
- [ ] Adresse IP invalide rejetée avec un message clair (`192.168.1`, `abc`, champ vide)
- [ ] IP valide mais aucun appareil : message d'erreur explicite, pas de plantage
- [ ] IP d'un appareil non-Shelly (ex. la box) : rejet propre
- [ ] Ajout de la Plug M : succès, modèle correctement rapporté
- [ ] Le nombre de canaux détectés est correct (1 pour la Plug M)
- [ ] Doublon (même IP + même canal) : refusé avec message
- [ ] Modification du nom : prise en compte immédiate sur le Tableau
- [ ] Suppression : confirmation demandée, tuile retirée
- [ ] Réordonnancement conservé après redémarrage de l'application

### 3bis. Prise — sonde automatique sur l'IP (lot 2026-09-23)

- [ ] IP valide saisie, puis pause ~1 s sans rien taper : sonde automatique déclenchée (spinner),
      sans avoir quitté le champ
- [ ] IP valide saisie, puis touche « Suivant »/« OK » du clavier : sonde déclenchée immédiatement
- [ ] Prise avec un nom déjà configuré : champ Nom absent, nom trouvé affiché en lecture seule
- [ ] Prise sans nom (jamais configurée) ou injoignable : champ Nom apparaît normalement
- [ ] IP corrigée après une sonde réussie : nouvelle sonde relancée, nom trouvé remasqué le temps
      du nouveau test
- [ ] Ajout réussi : bouton grisé + spinner, puis nom réel affiché quelques secondes, puis
      fermeture automatique de l'écran
- [ ] Appareil multi-canaux (Strip4) : sélection des canaux toujours fonctionnelle, confirmation
      finale identique
- [ ] Permission réseau local refusée : la sonde automatique ne se déclenche pas (aucun popup
      pendant la saisie), seul le tap sur Ajouter déclenche la demande de permission, comme avant

### 3ter. Détecteur de fumée — réveil manuel à l'ajout (lot 2026-09-23)

- [ ] IP et Nom saisis : le texte d'instruction (réveil 3 clics) n'apparaît qu'une fois les deux
      champs remplis
- [ ] Bouton Ajouter invisible pendant les 3 s qui suivent l'apparition du texte, puis apparaît
- [ ] Détecteur réveillé (3 appuis brefs) avant de taper Ajouter : le vrai nom de l'appareil est
      récupéré et affiché en confirmation (pas le texte saisi)
- [ ] Détecteur non réveillé / rendormi entre-temps : ajout quand même réussi (jusqu'à ~5 s
      d'attente), nom saisi poussé sur l'appareil (comportement de repli), confirmation affiche ce
      nom saisi
- [ ] Doublon (même IP) refusé avec message, comme pour une prise

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
- [ ] **Puissance instantanée** affichée en haut à droite de la tuile pour un appareil qui
      mesure : `0.0 W` au repos (point décimal, même en français), valeur réelle en charge,
      mise à jour à chaque relevé
- [ ] Un appareil **sans mesure** n'affiche aucune puissance ; une prise **hors ligne** non plus
- [ ] Tuile Actif avec seuil de coupure à côté d'une tuile Indisponible : même hauteur dans la
      grille, pas de décalage visible entre les deux colonnes

### 4bis. Vérification automatique du firmware (lot 2026-09-23)

- [ ] Mode démo (appui long sur le logo) : bandeau bleu « Maj dispo » visible sur la tuile
      « Living Room Lamp », absent sur les autres appareils factices
- [ ] Tap sur le bandeau : ouvre Modifier l'appareil, défile automatiquement jusqu'à la section
      Firmware, relance une vérification toute seule (spinner bref)
- [ ] Bandeau affiché **une seule fois** sur un bloc multiprises (pas répété par mini-cercle)
- [ ] Un appareil réel avec le Cloud désactivé : jamais de vérification automatique, jamais de
      bandeau, même après plusieurs relances de l'appli
- [ ] Un appareil réel avec le Cloud activé et déjà à jour : pas de bandeau
- [ ] Après une installation lancée (bouton Installer) : le bandeau disparaît immédiatement du
      Tableau, sans attendre la prochaine vérification automatique

## 5. Minuteur

- [ ] `1h` : la prise s'allume, le compte à rebours démarre et décroît
- [ ] Le compte à rebours reste cohérent après rotation de l'écran
- [ ] Le compte à rebours reste cohérent après mise en arrière-plan puis retour
- [ ] **Fermer complètement l'application** : la prise se coupe bien à l'échéance
- [ ] **Éteindre le téléphone** pendant un minuteur : la prise se coupe bien à l'échéance
- [ ] Le compte à rebours est correct après réouverture de l'application
- [ ] Annulation d'un minuteur en cours : la prise s'éteint et le compte à rebours disparaît
- [ ] **Non-régression (bug du minuteur persistant)** : laisser un minuteur aller **jusqu'à son
      terme**, puis rallumer la prise avec le simple interrupteur → elle doit **rester allumée**
      indéfiniment, sans coupure automatique
- [ ] Même vérification depuis le **bouton physique** de la prise après un minuteur terminé
- [ ] `Switch.GetConfig` de la prise : `auto_off` doit rester à `false` après usage du minuteur
      (`curl -s "http://<ip>/rpc/Switch.GetConfig?id=0"`)
- [ ] Rouleaux « Perso » : sélection fluide, valeurs `0-23` et `0-59`
- [ ] Durée de 0 minute refusée
- [ ] Durée longue (ex. 12 h) acceptée et correcte

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
- [ ] **Tuile du Tableau** : « Mode présence » en orange, plage horaire en dessous, sur **une
      seule ligne** (vérifier sur l'écran le plus étroit — Sony) pour ne pas casser l'alignement
- [ ] Le voyant de la tuile reste **plein/creux** selon l'état réel pendant la simulation
- [ ] **Appui sur l'interrupteur pendant une simulation** : le dialogue apparaît, avec la plage
      horaire rappelée
- [ ] `Arrêter la simulation` : le script est retiré de la prise, la bascule demandée s'applique,
      la tuile repasse en « Actif » / « Repos » et **la prise ne se rallume plus toute seule**
- [ ] `Annuler` : aucun effet, ni sur la prise ni sur le script
- [ ] Après arrêt, les horaires sont toujours mémorisés (redéploiement possible sans les ressaisir)
- [ ] Le Tableau ne ralentit pas malgré la lecture supplémentaire de l'état des scripts
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

## 9. Planning (composant Schedule)

- [ ] Section « Planning » visible sur le détail d'un appareil commutable, vide au départ
- [ ] Ajouter un planning (ex. 09:00–17:00, tous les jours) : il apparaît dans la liste
- [ ] Vérifier côté prise : `curl -s -X POST http://<ip>/rpc -H 'Content-Type: application/json'
      -d '{"id":1,"method":"Schedule.List"}'` → **2 programmes** (allumage + extinction)
- [ ] Fermer et rouvrir l'écran : le planning est **relu depuis la prise** (pas mémorisé localement)
- [ ] Choix de jours précis (ex. Lun–Ven) : correctement affiché et relu
- [ ] Fin avant début : bouton Valider désactivé, message d'erreur
- [ ] Aucun jour sélectionné : refusé
- [ ] **Conflit planning ↔ planning** : un créneau qui chevauche un existant est refusé avec message
- [ ] **Conflit planning ↔ présence** : refusé si le mode présence est actif
- [ ] Supprimer un planning : ses **deux** programmes disparaissent de la prise
- [ ] **Éditer** un planning (tap sur ses horaires) hors créneau : dialogue pré-rempli,
      la validation remplace les 2 programmes (vérifier au curl : nouveaux horaires, anciens partis)
- [ ] **Édition bloquée** : taper un planning **en cours** (heure actuelle dans son créneau) →
      message « Planning en cours », pas de dialogue
- [ ] **Suppression d'un planning en cours** : le popup ajoute l'avertissement « la prise restera
      allumée », et après suppression la prise **reste bien allumée**
- [ ] Édition sans conflit avec soi-même : garder les mêmes horaires en éditant → accepté (pas de
      faux conflit)
- [ ] 10 plannings : le bouton d'ajout se grise
- [ ] Le planning se déclenche réellement à l'heure dite (test avec un créneau proche), et
      **survit à un débranchement/rebranchement** de la prise

### 9bis. Notifications ntfy des plannings et minuteurs (lot 2026-09-29)

- [x] Minuteur « Active pour » **sans seuil** sur deux canaux d'une Strip4 à quelques secondes
      d'écart : une notification de fin par canal (script partagé `hestia_timer_notify`) — validé
      le 2026-10-03
- [x] Planning **avec seuil** coupé avant son heure de fin (prise à vide, seuil 10 W, 3 min) :
      « Coupure sur seuil » après 1 min, **aucune** notification « planning terminé » ensuite —
      validé le 2026-10-03
- [ ] **Non testé faute de charge** : planning avec seuil qui va jusqu'à son heure de fin sans
      coupure (charge > seuil pendant tout le créneau, ex. lampe 20 W avec seuil 5 W) → une seule
      notification « planning terminé » envoyée par `planEnd()`. Si le cas se présente en vrai et
      que la notification manque : 5 taps sur le titre du Tableau, envoyer le journal ; piste =
      l'OFF-job du planning doit appeler `Script.Eval planEnd()` (voir `DeviceRepository`,
      `createPlanning`/`updatePlanning`) et le script `ChargeScriptGenerator.generate` doit être
      encore actif à cette heure.

## 10. Thèmes et affichage

- [ ] Thème clair : tous les textes lisibles, aucun contraste douteux
- [ ] Thème sombre : idem
- [ ] Bascule du thème système pendant l'utilisation : pas de plantage, rendu correct
- [ ] Chaque état est accompagné d'un libellé texte, jamais de la seule couleur
- [ ] Grande police système : aucun texte tronqué ni chevauchement
- [ ] Rotation de l'écran sur tous les écrans : pas de perte d'état
- [ ] Écran de petite taille (Sony) : grille lisible, boutons atteignables au pouce
- [ ] Réglages → Langue : bascule Système → English → Français → Русский → Système, appliquée immédiatement sur tous les écrans
- [ ] Re-sélectionner la langue déjà active : rien ne se passe (pas de clignotement)
- [ ] Android 13+ (Pixel) : changer la langue de Hestia dans Paramètres → Langue de l'appli ; Réglages → Langue reflète le choix
- [ ] Android 11–12 (Sony) : langue forcée conservée après rotation, bascule du thème sombre et redémarrage de l'appli
- [ ] Android 11–12 (Sony) : retour à « Suivre la langue du système » → langue du téléphone, pas la dernière langue forcée
- [ ] Notification Android (worker) et texte ntfy d'un minuteur nouvellement armé : dans la langue choisie, pas celle du système
- [ ] Minuteur (Détail) : 30 min / 1 h / 2 h / Manuel → la lampe s'allume, « … · extinction dans 0:29:59 » sur le Détail et la tuile, puis s'éteint seule à l'échéance, même appli fermée.
- [ ] Éteindre pendant le minuteur l'annule (plus de décompte, pas de rallumage).
- [ ] **À vérifier sur vrai matériel** : bouger le curseur pendant un minuteur — le décompte continue-t-il ou l'appareil l'annule-t-il ? (L'écran affiche ce que fait l'appareil ; le mode démo suppose qu'il continue.) Idem pour Manuel → « Sans limite » pendant un minuteur.
- [ ] Renommer un variateur (Modifier ou Réglages) : le nom apparaît dans l'interface web de l'appareil / l'appli Shelly ; appareil injoignable au moment du renommage → nom poussé au retour sur le Tableau.
- [ ] Nom changé depuis l'interface web de l'appareil → repris par Hestia au passage par Réglages.
- [ ] Appareil multi-light (RGBW en mode light) sans noms : chaque canal reçoit son nom sur l'appareil. Appareil dont les canaux sont déjà nommés : noms inchangés après l'ajout.
- [ ] Mode démo : toujours en anglais ; en sortant, retour à la langue choisie
- [ ] Russe : pluriels corrects dans Réglages (2 канала, 5 каналов) et textes ntfy en cyrillique lisibles sur le téléphone

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

## Variateurs (fork, 2026-10-07)

- [ ] Ajout d'un variateur (Dimmer Gen3/Gen4, Plus Wall Dimmer…) via la tuile « Variateur » : ajouté, nom lu sur l'appareil.
- [ ] Même appareil ajouté via « Prise / relais » : même résultat (ce sont les composants qui décident).
- [ ] Relais (Plug M) ajouté via la tuile « Variateur » : enregistré et affiché comme « Prise / relais » (pas Variateur).
- [ ] Appareil mixte (≥ 2 relais + un light, même IP) : bandeau multi-canaux des relais intact, quel que soit l'ordre ; un seul light en tuile séparée, 2 lights ou plus sur la même IP en leur propre ligne de cercles.
- [ ] Mise à jour firmware disponible : bandeau sur la tuile du variateur.
- [ ] Plus RGBW PM en mode light : une seule ligne de 4 cercles. En mode rgb/rgbw : refus « mode couleur ».
- [ ] Tuile : « Allumée · N % » / « Éteinte » / « Indisponible » ; bouton marche/arrêt OK ; tap → Détail.
- [ ] Détail : curseur, envoi au relâchement seulement ; lampe éteinte + curseur → s'allume à ce niveau.
- [ ] Appareil débranché pendant un réglage : message « injoignable », curseur revient à la valeur réelle.
- [ ] Aucun minuteur / planning / présence proposé pour un variateur.
- [ ] Réglages : variateur « En ligne » quand il répond.
- [ ] RGBW en mode light : une seule ligne de 4 cercles (nom du groupe, état de chaque canal en texte, décompte du minuteur) ; tap sur un cercle → Détail du canal.
- [ ] Mise à jour par-dessus une version sans variateurs (base v17) : aucun appareil perdu.
- [ ] Mode démo : « Bedroom Dimmer » allumé à 40 % au départ ; bouton et curseur changent l'état.
