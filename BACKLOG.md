# Backlog — Hestia

Points relevés en cours de route, à traiter dans un lot ultérieur (pas des bugs bloquants).
Dernière mise à jour : 2026-09-03 (nettoyage : la section « En cours de traitement » ne contenait
plus que du travail déjà livré et jamais déplacé — fusion Planning/Présence, refonte des scripts
partagés, chantier détecteur de fumée (Lots 1-6, voir `SMOKE-DETECTOR.md`), conflit bouton/
présence — tout regroupé sous « Fonctionnalités futures » en une ligne chacun, détail conservé
dans l'historique git).

## Fait — en cours (à surveiller)

- **Corrigé le 2026-09-03** (retour du 2026-09-01) : à la création d'un planning, le bouton
  passait à une coche grisée pour dire « c'est enregistré », mais le texte restait « Enregistrer ».
  Vérifié avant de toucher : le même texte est utilisé à 2 autres endroits (Perso, Modifier
  l'appareil) mais sans cette transition coche/grisé — seul `planning_validate` avait besoin d'un
  second texte (`planning_validate_done`, affiché uniquement pendant l'appel réseau).

- **Bug trouvé et corrigé le 2026-08-24, en testant le Lot 3 du conflit bouton/présence (voir plus
  bas) : la détection d'appui bouton se redéclenchait en boucle sur une source périmée, coupant
  toute présence/planning fraîchement créé avant même son premier allumage.** `DashboardViewModel.
  fetch()` détecte un appui bouton physique (pour couper présence/planning « aujourd'hui ») en
  regardant si le canal est **actuellement** éteint avec `source` de type bouton — sans vérifier
  que c'est un appui **récent**. Or `Switch.GetStatus.source` reste figé sur la dernière
  transition connue tant qu'aucune autre n'a lieu, potentiellement des heures. Vécu en direct :
  prise 2 et 4 avaient toutes deux une vieille source bouton (tests curl du Lot 1) alors qu'elles
  étaient éteintes depuis longtemps — en créant une présence dessus, la détection l'a coupée
  « pour aujourd'hui » au cycle de sondage suivant (5 s plus tard), *avant même qu'elle ait pu
  s'allumer une seule fois* ce jour-là, et continuait de la recouper à chaque cycle malgré une
  recréation complète du planning (`STATE` du script présence confirmé côté appareil :
  `off` figé avant `on`, incohérent avec un vrai cycle). Corrigé en exigeant une vraie transition
  allumé→éteint entre deux cycles de sondage (comparaison avec `statuses.value` du cycle
  **précédent**, capturé avant écrasement) plutôt qu'un simple « actuellement éteint avec source
  bouton ». Contrepartie acceptée : un appui survenu pendant que l'app était fermée n'est plus
  rattrapé au premier relevé suivant (rien à comparer) — la présence continue de tourner en
  autonomie sur l'appareil quoi qu'il arrive, l'utilisateur peut toujours couper à la main.
  Après ce correctif, les présences de test cassées sur prise 2 et 4 doivent être réenregistrées
  une dernière fois (`STATE` du script présence remis à neuf au passage) pour repartir propre.

- **Régression trouvée et corrigée le 2026-08-22 : le mémo « désactivé aujourd'hui » restait
  collé après suppression/recréation de la présence ou du planning.** Vécu en direct par David
  sur les deux prises testées : supprimer une présence désactivée puis en recréer une nouvelle
  affichait quand même « Présence désactivée aujourd'hui » (couleur y compris, plus aucun indigo/
  violet visible nulle part) — le mémo (`AppPreferences.isPresenceDisabledToday`/
  `isPlanningDisabledToday`) n'est lié qu'au canal et à la date, pas à une présence/planning
  précis, donc rien ne le réinitialisait à la création d'une config totalement nouvelle. Corrigé
  en ajoutant `clearPresenceDisabledToday`/`clearPlanningDisabledToday`, appelés à chaque création
  **et** modification réussie (`DeviceRepository.createPlanning`/`updatePlanning`, les deux
  branches présence et précis) — une présence ou un planning tout juste (re)créé repart toujours
  neuf. Suppression seule non traitée (pas nécessaire : sans présence/planning, la branche
  « désactivé » de `DeviceTile.toVisual` ne peut de toute façon plus s'activer).

- **Bug de fond trouvé et corrigé le 2026-08-22 : un planning créé (ou modifié) alors que son
  créneau couvre déjà l'instant présent ne s'applique jamais tout seul.** Découvert en creusant
  un faux-négatif apparent (un canal noté « Planifié » mais réellement éteint, repéré grâce au
  nouvel anneau des tuiles qui reflète le fait physique indépendamment du régime affiché en
  texte/centre — voir refonte couleurs ci-dessous). Cause identifiée par David lui-même en testant
  en direct : `createPlanning`/`updatePlanning` ne font que poser deux programmes cron
  (`Schedule.Create` allumage + extinction) — un programme cron ne déclenche qu'à sa **prochaine**
  occurrence, jamais rétroactivement. Créer un planning « 9h–19h » à 17h30 ne fait donc **rien**
  aujourd'hui : le programme d'allumage de 9h est déjà passé, le prochain sera demain 9h (si
  récurrent) — la tuile affiche pourtant « Planifié » tout de suite, puisque `isActiveNow()`
  compare seulement l'heure du téléphone au créneau configuré, sans jamais vérifier que l'appareil
  a réellement obéi. Pire pour un Unique créé après son heure de départ : son programme ne se
  déclenchera alors plus **jamais**, une seule occurrence ratée pour toujours.
  **Corrigé** : `createPlanning`/`updatePlanning` appliquent maintenant immédiatement
  `Switch.Set(on:true)` si le créneau qui vient d'être créé/modifié couvre déjà l'instant présent
  (nouvelle fonction `DeviceRepository.applyIfAlreadyActive`, réutilise `Planning.isActiveNow()` —
  même calcul que l'affichage, aucune divergence possible entre ce qui est montré et ce qui est
  appliqué). Best-effort, ne fait jamais échouer la création/modification si ce rattrapage rate.
  Pas encore testé en direct (identifié et codé en fin de session, David n'a plus le temps ce
  soir) — à valider : créer un planning couvrant l'instant présent doit désormais allumer la prise
  tout de suite, pas seulement colorer la tuile.

- **Bug de fond découvert en direct le 2026-08-21 : les modifications par `Script.Eval` sur un
  script superviseur ne survivent jamais à un redémarrage matériel — priorité haute, pas encore
  planifié.** Vécu en conditions réelles sur la Strip4 : présence configurée sur la prise 2 (un
  reliquat de tests antérieurs, jamais visible dans Hestia tant que l'écran Détail de ce canal
  précis n'avait pas été ouvert), supprimée depuis l'app, puis la prise déplacée (débranchée/
  rebranchée) — **la présence est revenue** telle qu'avant la suppression.
  Cause racine, confirmée par deux lectures différentes de l'état du script : `Script.GetCode`
  lit le **texte enregistré sur la flash** (celui du tout premier déploiement) ; `Script.Eval`
  (utilisé pour tout ajout/retrait/modif de canal sans redéployer, afin de ne jamais perturber les
  autres canaux déjà suivis) ne modifie que la **mémoire vive du script en cours d'exécution** —
  jamais ce texte enregistré. Les deux divergent dès la première modification faite après le
  déploiement initial, et rien ne les resynchronise ensuite. Piège pour le diagnostic lui-même :
  `Script.GetCode` semblait « prouver » que la suppression avait échoué (il montrait toujours
  l'ancienne config) alors qu'elle avait réellement eu lieu en mémoire (confirmé par `Script.Eval`
  avec `JSON.stringify(CFG)`, qui lit le véritable état vivant) — **c'est ce dernier qu'il faut
  toujours utiliser pour vérifier l'état réel d'un script superviseur, jamais `GetCode`.**
  Un redémarrage matériel (coupure secteur, déplacement de la prise — pas un cas rare en usage
  réel) fait donc **toujours** revenir un script superviseur (présence, minuteur bouton — tous
  deux censés être persistants) à sa toute première config déployée, silencieusement, quel que
  soit le nombre de modifications faites depuis. Déjà noté comme limite acceptée pour les *ajouts*
  de canal (« cas rare, sans danger », lot B/A bis du chantier script superviseur) — se révèle
  tout aussi vrai pour les *suppressions*, et bien plus gênant en pratique que prévu.
  **Correctif ponctuel appliqué manuellement sur la Strip4 le jour même** (`Script.Stop` +
  `Script.PutCode` avec un `CFG` vide correspondant à l'état réel + `Script.Start`) — réaligne le
  texte enregistré sur la mémoire vive pour ce script précis, corrige le symptôme sur cet appareil,
  ne corrige pas la cause. Le vrai chantier reste entier : rendre les modifications persistantes
  sans perdre l'état vivant des autres canaux — exactement le compromis qui avait fait choisir
  `Eval` plutôt qu'un redéploiement complet au départ. Pistes à peser le jour où on s'y attaque :
  redéployer le texte complet (via `PutCode`) à chaque modification significative plutôt qu'au
  seul premier déploiement (mais `PutCode` exige `Stop` au préalable — perd la mémoire vive des
  *autres* canaux, déjà testé et refusé pour cette raison) ; ou un mécanisme de resynchronisation
  périodique qui réécrit le texte depuis l'état vivant sans jamais arrêter le script en cours
  (à explorer, aucune piste validée pour l'instant).
  **Trouvé au passage, plus petit** : impossible de modifier un planning/présence une fois mis en
  pause (`PausedPlanningRow` n'offre que Reprendre/Supprimer, jamais Éditer) — pas de chemin pour
  changer les horaires d'un planning actif sans passer par une suppression puis recréation
  complète. À corriger, sans lien avec le bug ci-dessus.
  **Lot 1 (validation curl du mécanisme de correctif) — fait le 2026-08-22, concluant.** Cycle
  complet validé en direct sur la Strip4 : lecture de l'état vivant (`CFG` **et** `STATE`) par
  `Script.Eval`, `Script.Stop`, `Script.PutCode` avec ces valeurs écrites en dur comme état
  initial, `Script.Start` — l'état redémarre **à l'identique** (vérifié sur des valeurs tirées au
  sort, impossibles à reconstruire : les horaires randomisés d'une plage de présence de test), la
  flash est enfin à jour, et le tout **survit à un vrai débranchement/rebranchement** de la prise
  (test final réussi : CFG et STATE intacts après coupure secteur). Interruption du script ~1-2 s
  par réalignement, invisible pour un timer à la minute.
  **Piège d'outillage découvert en route, sans impact sur l'app** : le parseur JSON embarqué de
  Shelly rejette les échappements `\uXXXX` (`-103 Missing or bad argument 'code'`) — il faut
  envoyer l'UTF-8 brut. Python `json.dumps` échappe par défaut (`ensure_ascii=True`), d'où des
  échecs trompeurs en test ; kotlinx.serialization envoie l'UTF-8 brut, l'app n'est pas concernée.
  **Observation à surveiller, non reproduite ensuite** : après une vraie coupure secteur (subie,
  pas provoquée — vers 09h30 le 2026-08-22), `hestia_presence` (`enable:true`) n'a PAS redémarré
  au boot alors que `hestia_button_timer` (`enable:true` aussi) si — même code en flash qui avait
  correctement redémarré au boot précédent. L'auto-démarrage des scripts au boot n'est donc
  peut-être pas fiable à 100 % côté firmware Shelly. Reproduit une seule fois ; les deux
  redémarrages testés ensuite ont fonctionné. Si ça se confirme un jour, c'est une variante de
  panne silencieuse indépendante de notre correctif (rien côté Hestia ne peut relancer un script
  sans tâche de fond) — à documenter plutôt qu'à « corriger ».
  **Lot 2 (`enable=false` pour les scripts transitoires) — codé et validé en test réel le
  2026-08-22** (minuteur à seuil lancé depuis l'app → `hestia_charge` déployé `enable=false` +
  `running=true` ; débranchement/rebranchement en plein minuteur → le script ne repart pas au
  boot, le bouton — persistant — repart bien, comportement exact attendu). `hestia_charge` et `hestia_timer_notify_<canal>` ne redémarrent plus jamais seuls au
  boot : le minuteur natif qu'ils accompagnent ne survit pas au redémarrage, eux non plus ne le
  doivent pas — aligne sur le script de coupure de planning, seul à faire déjà `enable=false`
  (l'incohérence entre les trois était révélatrice). Deux chemins pour charge : le déploiement
  complet, et un rattrapage dans le chemin `Eval` (`Script.SetConfig enable=false` sans arrêter
  le script — enable ne joue qu'au boot) pour les scripts déjà déployés en `enable=true` sur le
  matériel existant, qui se corrigent ainsi au premier minuteur à seuil relancé.
  **Lot 3 (réalignement flash après chaque `Eval`, présence + bouton) — codé le 2026-08-22, pas
  encore testé.** `PresenceScriptGenerator.generateSupervisor`/`ButtonTimerScriptGenerator.
  generate` gagnent un paramètre `initialStateJson` optionnel (défaut null = comportement
  inchangé, état vierge) : quand fourni, le `STATE` initial du script généré est l'état vivant
  lu juste avant, pas un état neuf — c'est ce qui permet au réalignement de ne rien perdre.
  Nouvelles `evalReadFull()`/`parseLiveSnapshot()` sur les deux générateurs : lisent `{cfg,state}`
  en un seul `Script.Eval` (mécanisme validé au Lot 1), `state` gardé en JSON brut, jamais
  interprété par Hestia — seule sa position par canal compte, elle voyage telle quelle du script
  vers le script.
  `DeviceRepository.realignPresenceFlash`/`realignButtonTimerFlash` : appelées après **chaque**
  `Script.Eval` réussi (ajout, retrait, ou modification d'un canal) sur un script déjà en cours —
  lisent le snapshot vivant, `Stop`, régénèrent le code avec cet état en dur, `PutCode`, `Start`.
  Sur `withContext(NonCancellable)` : une fois commencée, la séquence va au bout même si l'écran
  appelant se ferme entre-temps (même risque que le bug de renommage ntfy corrigé le 2026-08-19 —
  jamais un script laissé à l'arrêt parce qu'un ViewModel a été détruit au milieu). Best-effort à
  chaque étape (snapshot illisible, `Stop`/`PutCode` refusés) : ne fait jamais échouer l'action
  utilisateur qui a déclenché la mutation, se contente de journaliser et laisser la flash en
  retard pour cette fois — pas pire qu'avant ce lot. Si le snapshot revient avec zéro canal (tout
  retiré), le script est supprimé plutôt que réécrit vide — libère un des 3 emplacements de
  script de l'appareil au passage.
  **Mécanisme revalidé le 2026-08-22 sur le vrai scénario ciblé** (curl, en attendant l'APK) :
  bootstrap d'une présence sur la prise 2, puis **modification pendant que le script tourne**
  (le chemin `Eval` que ce lot corrige) — la flash reflète bien la modif après réalignement,
  l'état vivant redémarre à l'identique du snapshot capturé juste avant, et le script continue
  de tourner normalement ensuite (tick suivant vérifié). Strip4 renettoyée après test.
  **Lot 4 (test réel complet avec l'app) — fait et concluant le 2026-08-22.** Scénario final,
  entièrement piloté depuis l'app installée : présence créée sur la prise 2 (14h56-15h05, marge
  15 min) — modification impossible pendant qu'elle tourne, confirmé volontaire (règle déjà
  actée) ; ajout d'une **seconde** présence sur la prise 4 pendant que le script tournait déjà
  pour la prise 2 (contourne la règle ci-dessus, exerce directement le chemin `Eval` +
  réalignement) — flash et mémoire vive identiques après coup, **l'historique déjà vivant de la
  prise 2 (horaires tirés au sort lors de son propre tick) intact**, rien réinitialisé. Débranchement/
  rebranchement réel avec la prise 4 allumée au moment de la coupure : script correctement
  redémarré (`reset_reason:1` confirmé), config des deux canaux identique avant/après, et la
  prise 4 s'est **rallumée toute seule** au tick suivant (`source:"loopback"`), preuve que la
  restauration n'est pas que des données mais un vrai comportement fonctionnel retrouvé.
  **Chantier persistance des scripts superviseurs considéré clos.** Les deux petits trous
  identifiés en route (planning en pause non éditable ; l'audit du firmware 2.0.0, déjà dans ce
  fichier) restent en attente séparément, sans lien avec ce correctif.

- **Étiquette « Présence » qui retombait sur « Actif » pendant la marge de fin — corrigé le
  2026-08-22.** Observé en conditions réelles sur la prise 2 : la fenêtre nominale d'une présence
  se terminait, la prise restait allumée (comportement correct — l'horaire réel avait été tiré au
  sort à +13 min dans la marge de ±15 min configurée), mais le Tableau affichait « Actif »
  générique au lieu de « Présence », `Planning.isActiveNow()` ne comparant qu'à la fenêtre
  nominale, sans connaître la marge. Pas un bug de planification (la prise suivait bien sa propre
  config), un défaut d'affichage seulement — confirmé en relisant l'état vivant du script.
  Corrigé en élargissant la fenêtre vérifiée de ±`marginMinutes` (nul pour un planning précis,
  sans effet) via un petit calcul sur ligne de temps continue (`window(dayOffset)`), qui se réduit
  strictement à l'ancien calcul quand la marge est nulle — voir [Planning.kt](app/src/main/java/kapoue/hestia/domain/model/Planning.kt).
  Effet de bord positif au passage, pas seulement cosmétique : `isActiveNow()` sert aussi à
  bloquer l'édition/la pause d'un planning « en cours » et à déclencher la confirmation « Arrêter
  la simulation ? » — ces protections couvrent désormais toute la plage où la prise peut
  réellement être encore pilotée par le script, pas seulement la fenêtre nominale. Pas encore
  testé en conditions réelles (attend le prochain cycle de présence avec marge) ; pas encore
  compilé (attend l'APK de David).

- **HTTPS forcée (`enhanced_security`) — chantier mis en pause le 2026-08-21, Lot 1 codé puis
  volontairement annulé (`git restore`) le jour même, jamais commité.** Raison : trop de
  chantiers enchaînés sans test réel — le bug de persistance des scripts superviseurs
  (ci-dessus), découvert en plein milieu, passe devant. À reprendre plus tard **en repartant de
  zéro côté code**, mais tout ce qui a été appris et décidé reste valable :
  - **Décision actée : épinglage TOFU (confirmation au premier contact), jamais d'acceptation
    aveugle.** Un `TrustManager` qui accepte tout est un motif de rejet connu (Google Play le
    bloque, F-Droid/linsui le repérerait) ; Home Assistant refuse aussi les certificats
    auto-signés plutôt que de les accepter en silence. Design retenu : lire le certificat sans le
    valider (poignée de main dédiée, aucune donnée échangée), afficher l'empreinte SHA-256 dans
    une boîte de dialogue, n'enregistrer l'épingle (`sha256/<base64 du SPKI>`, format
    `CertificatePinner` d'OkHttp) qu'après confirmation explicite ; ensuite épinglage strict, et
    alerte claire si le certificat change un jour.
  - **Piège technique validé en direct sur un faux serveur** (script Python + certificat
    auto-signé, recréable à la demande — supprimé depuis) : les redirections 301/302/303
    transforment silencieusement un POST en GET **en perdant le corps JSON-RPC** (comportement
    standard, OkHttp comme curl) ; seuls 307/308 préservent la méthode, et le code exact renvoyé
    par un vrai appareil Shelly est inconnu (non documenté). Conclusion ferme : client HTTP local
    dédié avec `followRedirects(false)`, détection manuelle du 3xx + `Location: https://…`, et
    relance explicite de la requête d'origine avec son corps.
  - **Parc actuel non concerné, vérifié en direct** : Plug M et Strip4 mis à jour vers 2.0.0
    (pas sortis d'usine avec), `enhanced_security: false` sur la Strip4, jamais activable par
    accident (et irréversible sauf réinitialisation d'usine — ne jamais l'activer pour tester ;
    David accepte de reconfigurer la Strip4 si un test réel final s'avère nécessaire).
  - Le squelette annulé couvrait : `@LocalRpcHttpClient` (NetworkModule), `RpcFailure.
    HTTPS_REQUIRED`, `ShellyRpcClient.peekCertificate()`, `CertPeek` (domain), boîte de dialogue
    dans Ajouter/Modifier, `Device.certPin` + migration v15→v16 — **la migration v16 n'a jamais
    été livrée, le numéro est de nouveau libre.**

## Écarté

- **Authentification de l'appareil (mot de passe Shelly).** Envisagé (saisie à l'ajout + client
  RPC en digest auth + stockage sécurisé), **écarté le 2026-07-26** : David n'en met pas sur son
  réseau privé. À reconsidérer seulement si des utilisateurs F-Droid le demandent.
- **Remplacer `material-icons-extended` par les icônes réellement utilisées** (10 sur les 17
  icônes de l'app n'existent que dans ce module, les 7 autres sont dans le module « core »).
  **Écarté le 2026-07-27** : le poids qu'il posait est déjà réglé par R8 (47 Mo → 3,6 Mo en
  release) ; l'extraction manuelle des tracés vectoriels (tentée par décompilation) s'est révélée
  peu fiable sur les icônes à courbes (Wifi, Sensors, Lightbulb), et un rendu cassé ne serait pas
  détectable sans capture d'écran après build. À reconsidérer seulement si le poids redevient un
  problème réel.
- **Journal d'activité par appareil (« Dernière activité »).** Retiré intégralement le
  2026-07-27 : entité `ActivationLog`, DAO, section d'écran, formatage. Tenté d'y attribuer une
  cause fiable (bouton, minuteur, planning, présence) via le champ `source` de l'appareil —
  fonctionnait bien app ouverte, mais un test délibéré (app tuée pendant tout le cycle) a confirmé
  la limite de fond : sans tâche de fond permanente, aucune observation n'a lieu, et si l'état
  final rejoint l'état de départ pendant l'absence, même le passage du worker de notifications
  (~15 min, lui-même limité aux utilisateurs ayant activé les notifications) ne voit rien à
  journaliser. Storer l'historique **sur la prise** (KVS) a été envisagé puis écarté : ne
  couvrirait que les bascules déclenchées par les scripts Hestia (présence, coupure sur seuil),
  jamais le planning natif (`Schedule`) ni le bouton physique — une complexité réelle pour une
  couverture qui resterait partielle. La colonne `cause` ajoutée à la table (migration v4→v5) est
  retirée avec la table entière (migration v5→v6, `DROP TABLE`) ; la colonne vestige
  `lastKnownOutput` sur `devices` reste en base (retrait par `DROP COLUMN` jugé trop risqué sur
  les versions de SQLite embarquées par Android 11, minSdk du projet) mais n'est plus utilisée.

## Fonctionnalités futures

- Ajout d'un appareil neuf sans sortir de Hestia (provisioning Wi-Fi direct depuis l'appli) : la
  prise se connecte temporairement à son propre point d'accès, envoie le Wi-Fi cible via
  `WiFi.SetConfig`, puis relit elle-même l'IP obtenue — sans scan réseau ni lecture de MAC (deux
  choses qu'une appli Android normale ne peut de toute façon plus faire proprement).

- **Couleur de « Coupure à X W » (seuil affiché pendant un minuteur en cours) — question de
  David le 2026-08-24, à trancher.** Actuellement en vert (couleur d'état, comme « Actif »),
  identique au comportement d'avant le correctif du même jour sur « désactivé aujourd'hui »
  (`TileVisual.thresholdTextColor`, ajouté précisément pour ce cas-là). David se demande si le
  même traitement (gris neutre) devrait s'appliquer ici aussi — pas de trace d'un accord antérieur
  généralisant la règle à tout texte secondaire, seulement au cas « désactivé aujourd'hui »
  (ce texte contredit l'état actif, contrairement au seuil qui décrit un attribut de l'état actif
  en cours). Deux lectures possibles : (a) garder le vert, cohérent avec l'état affiché ;
  (b) gris partout en texte secondaire, par cohérence visuelle systématique. À trancher avec
  David, puis appliquer via `thresholdTextColor` (déjà en place, juste à renseigner pour cette
  branche aussi si le gris est retenu).

- **Détecteur de fumée/incendie Shelly — ✅ fait, testé et validé en conditions réelles
  (Lots 1 à 6 livrés le 2026-09-03).** Type d'appareil dédié, tuile Tableau, fiche Détail
  (mute, batterie, coupure de prise sur alarme réelle), relais ntfy opportuniste (le webhook
  natif du détecteur ne sait pas faire de POST). Détail complet dans `SMOKE-DETECTOR.md`.

- **Conflit minuteur bouton / présence sur un même canal — ✅ fait, testé et validé en
  conditions réelles (2026-08-24 au 2026-09-01).** Un canal gouverné par une présence ne peut
  plus armer son propre minuteur bouton pendant la fenêtre (blocage poussé par l'app au script
  du bouton), avec déblocage immédiat si la présence est arrêtée pour la journée — validé en
  direct sur la Strip4 (minuteur tenu 22 min sans recut après déblocage).

- **Enregistrer sans picto disquette + retour visuel pendant l'enregistrement — ✅ fait,
  confirmé en usage réel.** Picto disquette sur le bouton Enregistrer de Modifier l'appareil
  (mode édition uniquement). Dialogue planning : le bouton se désactive et affiche une coche
  pendant l'appel réseau (empêche un double-clic de créer un doublon) — texte passé à
  « Enregistré » pendant cette phase le 2026-09-03 (retour David : la coche + « Enregistrer »
  au présent laissait penser que rien ne s'était passé). Réglages n'a aucun bouton « Enregistrer »
  littéral (ntfy s'enregistre à la perte de focus, Cloud a « Tester »/« Effacer »).

- **Export/import : la présence n'était plus sauvegardée du tout depuis la fusion Planning/
  Présence du 2026-08-18 — trouvé le 2026-08-24, corrigé le même jour.** `BackupManager` lisait
  encore `PresenceConfigDao`/l'entité Room `PresenceConfig`, mais plus rien n'écrivait dans cette
  table depuis la fusion (la présence vit exclusivement dans le script de l'appareil, comme un
  planning) — tout export produit depuis le 18/08 avait donc une présence vide, silencieusement.
  Tranché avec David : suppression complète (`PresenceConfig`/`PresenceConfigDao`/
  `getPresenceConfig`/`presenceConfigs` dans `BackupFile`, migration Room v15→v16 `DROP TABLE`)
  plutôt qu'une réécriture en lecture RPC — aligne la présence sur le même principe que planning
  précis et minuteur bouton (jamais sauvegardés non plus, ils vivent sur l'appareil). Un ancien
  export portant encore ce champ reste lisible (`ignoreUnknownKeys`), simplement ignoré.

- **Bande vide au-dessus de la barre de navigation basse — signalée par David le 2026-08-24,
  corrigée le même jour.** Cause trouvée après une seconde capture plus précise : Tableau/
  Réglages/À propos reprenaient tout `innerPadding` de leur propre `Scaffold` (haut ET bas) alors
  que le bas est déjà réservé une seule fois par `HestiaApp` pour la barre de navigation
  partagée — `Scaffold` réserve par défaut un espace bas pour les barres système même sans
  `bottomBar` propre, doublant l'espacement. Les trois écrans ne reprennent plus que le haut.

- **Captures d'écran F-Droid à mettre à jour — demandé par David le 2026-08-24.** L'app a
  visiblement évolué depuis les dernières captures (`fastlane/metadata/`) — refonte des tuiles,
  nouveaux libellés d'état, etc. Pas commencé.

- **Build reproductible — Niveau 1 vérifié le 2026-08-22, suite à une issue Codeberg (#2, ouverte
  par un tiers).** Relecture de la config au regard du guide F-Droid : versions de dépendances
  toutes figées (`libs.versions.toml`, aucune plage), Gradle/AGP/Kotlin/KSP en versions exactes,
  aucun asset PNG/raster (tout en vecteur, pas de souci de compression non-déterministe),
  `versionCode`/`versionName` déjà en littéraux durs — rien à modifier côté code. Confirmé par un
  vrai test : deux `./gradlew clean assembleDebug` consécutifs, contenu de l'APK comparé
  (`diff -rq`, hors `META-INF`) — **identique**. Nuance : test sur la même machine, cache Gradle
  partiellement réutilisé (le second build n'a réexécuté que 16 tâches sur 42) — pas un « à froid »
  sur deux machines différentes, mais le résultat (contenu identique) reste la preuve qui compte.
  **Niveau 2 (upload d'un APK auto-signé par David pour publication F-Droid plus rapide) écarté
  délibérément** : demanderait de gérer une clé de signature (secret à haute valeur, jamais côté
  Claude) et une étape manuelle à chaque publication — David ne le souhaite pas vu le rythme de
  publication actuel. Le vrai goulot ressenti (délai de publication F-Droid) ne serait de toute
  façon résolu que par le Niveau 2, jamais par le Niveau 1 seul — clarifié explicitement avant de
  trancher, pour ne pas laisser croire à un gain de rapidité qui n'existe pas.

- **Couper une présence « pour aujourd'hui seulement », sans toucher aux jours suivants — bouton
  de l'app codé et fait le 2026-08-22 ; bouton physique reste au backlog.** Idée de David : le
  précédent correctif (le bouton ON/OFF arrête la présence en cas d'appui pendant qu'elle tourne)
  la coupait pour de bon (retire le canal du script, tous les jours programmés) — trop radical
  pour « je rentre à la maison, j'éteins pour aujourd'hui ». Exemple donné : présence 10h–19h,
  retour à la maison à 15h, on coupe pour aujourd'hui seulement, demain ça repart de 10h.
  - **Bouton de l'app — fait.** `hestia_presence` garde en mémoire vive, par canal, l'heure
    d'extinction **du jour** déjà tirée au sort (`STATE[i].off[j]`, recalculée chaque nouveau jour
    par `planDay()` à partir de la config permanente `CFG[i].windows`, jamais touchée). Nouvelle
    fonction `PresenceScriptGenerator.evalStopToday(switchId)` : ramène `STATE[i].off[j]` à
    l'instant présent pour la fenêtre en cours puis éteint la prise, via un seul `Script.Eval` —
    aucune mutation de `CFG`, `planDay` repart de lui-même de la config permanente le lendemain.
    `DeviceRepository.stopPresenceForToday` / `DashboardViewModel.stopPresenceToday` remplacent
    l'arrêt définitif comme action du bouton ON/OFF ; l'arrêt définitif
    (`DeviceRepository.stopPresence`) reste disponible ailleurs (suppression explicite depuis
    l'écran Détail, `DetailViewModel`, non touchée). Boîte de dialogue et libellés mis à jour en
    conséquence (« Couper pour aujourd'hui », plus « tous les jours programmés »).
  - **Rendu tuile clarifié — fait le 2026-08-22, révisé deux fois dans la soirée.** D'abord fait
    en gardant la couleur indigo (juste le texte changeait, « Présence · désactivée
    aujourd'hui ») ; David a ensuite demandé l'alignement complet sur Planning (voir ci-dessous) :
    plus aucune couleur de régime une fois désactivée, juste Actif/Éteint selon l'état réel, avec
    « Présence désactivée aujourd'hui » en texte fixe sur sa propre ligne
    (`tile_presence_disabled_today`, `thresholdText`, même mécanique que le seuil) — branche
    dédiée dans `DeviceTile.toVisual`, prioritaire sur la branche « présence » normale. Mémo local
    `AppPreferences.isPresenceDisabledToday`/`markPresenceDisabledToday` (clé = date du jour,
    s'efface tout seul le lendemain — ne couvre que le bouton de l'app, pas encore le bouton
    physique ci-dessous).
  - **Bug de fiabilité trouvé et corrigé le 2026-08-22 : la coupure du jour ne survivait pas à
    un redémarrage du script.** `stopPresenceForToday` mutait `STATE` (via `evalStopToday`) sans
    jamais réaligner la flash (`Script.PutCode`) — exactement le bug de fond corrigé la veille
    pour les autres mutations de présence (voir plus haut, « chantier persistance des scripts
    superviseurs »). Vécu en direct par David : la prise se rallumait d'elle-même après une
    coupure censée tenir jusqu'au lendemain (confirmé par `counts.switch_on` qui avait bien
    incrémenté entre deux vérifications). Corrigé en appelant `realignPresenceFlash` juste après
    la mutation, comme les autres chemins de mutation de présence.
  - **Écran Détail aligné — fait le 2026-08-22, en repassant derrière.** Le badge du haut et le
    tag « En cours » de la liste des plannings ignoraient ce mémo (calcul purement horaire,
    `isActiveNow()` seul) — une présence désactivée pour aujourd'hui s'y affichait quand même
    comme active. Nouveau `DetailViewModel.isPresenceDisabledToday()` (lecture locale, pas de
    RPC), branché sur **tous** les usages de `isActiveNow()` de l'écran (badge, tag « En cours »,
    blocage d'édition, blocage de mise en pause, avertissement de suppression) via un helper local
    `Planning.isReallyActive()`.
  - **Bouton physique — fait le 2026-08-22, plus léger que le plan initial.** Le script ne
    regarde jamais la provenance d'un changement (`source`) — contrairement au minuteur bouton,
    il compare juste « censé être dans une fenêtre » à « la prise est allumée » et **réimpose**
    l'état voulu à chaque tick, sans distinguer un appui bouton d'une coupure de courant à
    corriger. Plan initial (réécrire la logique interne du script partagé, `isButtonSource` +
    `matchedWindowIdx`) écarté après diagnostic en direct sur la Strip4 (appui physique constaté :
    coupe le courant, mais rien ne bouge côté app et la prise se rallume au tick suivant, comme
    prévu) — **abandonné pour une solution plus légère**, repérée en discutant avec David : la
    différence avec Planning n'est pas la détection (identique, `source` du relevé de 5 s) mais
    l'action. Détecter suffisait pour Planning (rien ne rallume la prise de son côté) ; pour
    Présence il faut aussi **déclencher réellement** la coupure, pas juste la mémoriser — mais pas
    besoin de toucher le script pour ça : `DashboardViewModel` appelle directement
    `DeviceRepository.stopPresenceForToday` (déjà écrit et validé pour le bouton de l'app) dès
    qu'il détecte un appui bouton pendant une présence active, avec une garde sur le mémo déjà
    posé pour ne pas rappeler à chaque cycle. Coûte un vrai aller-retour RPC, mais seulement dans
    ce cas précis et rare, jamais pour tous les canaux. Détection en ≤5 s (cycle du Tableau) au
    lieu de ≤60 s si on était passé par le script.

- **Même principe étendu à Planning — bouton app **et** bouton physique faits le 2026-08-22, dans
  la foulée de Présence.** Contrairement à la présence, un planning ne dépend d'aucun script
  Hestia (juste des programmes cron natifs) : couper manuellement pendant sa plage est déjà sans
  risque (le programme d'extinction, redondant, ne fait rien de plus). Demande de David : ne plus
  montrer la couleur « Planifié » (violet) une fois coupé pour aujourd'hui — juste Actif/Éteint
  selon l'état réel, avec un texte fixe l'indiquant — et couvrir aussi le bouton physique, pas
  seulement l'app (contrairement à Présence, dont le bouton physique reste un chantier à part
  ci-dessus). Jamais posé pour un planning **Unique** (pas de « lendemain » à distinguer).
  - **Détection, sans rien changer côté appareil.** Nouveau champ `TileStatus.Online.source`
    (déjà dans chaque relevé `Switch.GetStatus`, jamais exposé jusqu'ici) — un appui bouton
    physique se reconnaît directement dans le cycle de 5 s existant : canal éteint, `source`
    du genre bouton (`button`/`short_push`/…), et un planning récurrent nominalement actif à cet
    instant → mémorisé (`AppPreferences.isPlanningDisabledToday`/`markPlanningDisabledToday`,
    même mécanique date-du-jour que côté présence). Pas de dialogue, pas d'action de l'app : la
    coupure elle-même est déjà faite par l'appui, on ne fait qu'enregistrer.
  - **Bouton de l'app** : `DashboardViewModel.toggle` pose le même mémo quand on éteint alors
    qu'un planning récurrent est en cours — pas de boîte de dialogue de confirmation
    (contrairement à Présence) puisque l'action est déjà sans risque.
  - **Rendu** : nouvelle branche prioritaire dans `DeviceTile.toVisual` (avant même le minuteur/
    présence/planning normal) — tant que le mémo est actif pour aujourd'hui, la tuile bascule sur
    le libellé/couleur Actif ou Éteint (jamais plus « Planifié » violet), avec « Planning désactivé
    aujourd'hui » en texte fixe, qu'il soit actuellement allumé ou pas — pour que le texte reste
    stable même si la prise est rallumée manuellement ensuite.
    **Retouché après premier essai** : le texte était d'abord concaténé au libellé sur une seule
    ligne (« Éteint · planning désactivé aujourd'hui », lu comme un charabia par David, et assez
    long pour pousser l'interrupteur en dessous sur la tuile prise seule) — déplacé sur sa propre
    ligne via `thresholdText` (même mécanique que le seuil), libellé capitalisé
    (`tile_planning_disabled_today` = « Planning désactivé aujourd'hui »).
  - **Écran Détail** : même branchement que pour Présence
    (`DetailViewModel.isPlanningDisabledToday()`, `Planning.isReallyActive()` étendu).

- **Le seuil configuré n'apparaît pas sur la tuile quand il n'y a pas de décompte — repéré le
  2026-08-22 en listant tous les états textuels de la tuile pour la refonte graphique envisagée
  par David.** Deux volets distincts, tranchés séparément :
  - **Formulation du seuil déjà affiché (cas `Planifié` + décompte) — faite.** Le suffixe brut
    `· 10 W` prêtait à confusion avec la vraie consommation instantanée affichée juste en dessous
    sur la tuile. Remplacé par le libellé déjà utilisé ailleurs dans l'app pour ce même réglage
    (`timer_preset_cutoff_detail`, « Coupure à 10 W ») plutôt qu'un nouveau mot inventé (« seuil »
    aurait rompu la cohérence avec l'écran Détail) — voir
    [DeviceTile.kt](app/src/main/java/kapoue/hestia/ui/screens/dashboard/DeviceTile.kt).
  - **Durée du ON en cours affichée sur « Actif » — Lot 1 codé le 2026-08-22, pas encore
    testé en direct.** En creusant l'idée de David (retenir l'heure de départ localement), trouvé
    mieux que ce qui avait été envisagé au départ : `Switch.GetStatus` renvoie déjà
    `counts.on_time`, un compteur natif cumulant les secondes ON côté appareil, indépendant de
    l'app. On retient sa valeur à chaque extinction observée (`AppPreferences.setOnTimeBaseline`) ;
    dès que le canal est vu allumé, la durée du ON en cours = valeur actuelle − dernière valeur
    retenue à l'extinction — exacte même après une app fermée ou hors réseau entre-temps,
    contrairement à un horodatage pris à la première ouverture qui voit le canal allumé. Pas de
    nouvel appel RPC : réutilise `Switch.GetStatus`, déjà relu à chaque cycle de 5 s pour tous les
    canaux. Nouveau champ `SwitchCounts.onTime` ([RpcModels.kt](app/src/main/java/kapoue/hestia/data/rpc/model/RpcModels.kt)),
    `TileStatus.Online.onTimeSec` (brut) → `TileUiState.onSinceElapsed` (dérivé, calculé dans
    `DashboardViewModel.fetch`, référentiel `SystemClock.elapsedRealtime` comme
    `timerEndsAtElapsed` mais pour un point de départ passé). Affiché « depuis 12:34 » (libellé
    explicite, `tile_on_since`) plutôt qu'un nombre nu — pour ne pas se lire comme un décompte qui
    descend, alors que celui-ci grimpe. Couvre tuile prise seule **et** modale du bloc (même
    `toVisual()` partagé).
    **Bug remonté en test réel le 2026-08-22, corrigé le jour même** : le point de départ était
    recalculé à **chaque** cycle de 5 s à partir de la dernière valeur de `on_time` — la précision
    du compteur natif n'étant pas garantie à la seconde près d'un cycle à l'autre, l'affichage
    dérivait (le compte à rebours semblait revenir en arrière en rouvrant la modale du bloc), et
    un « kill » de l'app pouvait le faire repartir de zéro. Corrigé en ne calculant le point de
    départ (`AppPreferences.onSinceEpoch`, en epoch ms) **qu'une seule fois par allumage** — au
    premier cycle où le canal est vu allumé avec une référence d'extinction connue — puis en le
    gelant jusqu'à la prochaine extinction observée. Persisté (epoch, pas `elapsedRealtime`, pour
    survivre correctement à un « kill » de l'app). Limite acceptée, documentée mais pas résolue :
    si l'app est fermée pendant un cycle **complet** éteint→rallumé (raté en entier, jamais observé),
    la durée affichée au retour surestimera en comptant aussi l'allumage précédent manqué — cas
    rare, pas traité dans ce lot. Autre limite acceptée : rien ne s'affiche tant qu'aucune
    extinction n'a encore été observée par Hestia pour ce canal (première utilisation, ou après
    réinstallation).

  - **Faux signalement au passage, corrigé quand même : la grâce de 15 min avant surveillance
    du seuil n'était dite nulle part — corrigé le 2026-08-22.** David a testé un minuteur « sans
    limite de durée » (seuil 5 W), constaté que la coupure ne se déclenchait pas et l'a d'abord
    pris pour un bug — en réalité `ChargeScriptGenerator`/`ButtonTimerScriptGenerator` attendent
    tous deux 15 min après l'allumage avant de commencer à surveiller la consommation (évite une
    coupure prématurée le temps qu'un appareil branché commence vraiment à tirer du courant),
    comportement volontaire déjà en place, jamais communiqué à l'écran. Ajouté une note explicative
    dans [DurationPickerSheet.kt](app/src/main/java/kapoue/hestia/ui/screens/detail/DurationPickerSheet.kt),
    affichée dès qu'on active « Sans limite de durée » — un seul composant partagé par les 4 usages
    (Manuel, Perso ×2, minuteur bouton), donc corrigé partout à la fois.

  - **Valeur du seuil sur « Actif » (Lot 2) — Cas A et Cas B codés, validés en direct et testés
    depuis l'app le 2026-08-22 (retours pris en compte : espacement seuil/décompte).**
    - **Cas A — déclenché depuis l'app (Perso/Manuel/toggle) : fait.** Un minuteur « sans limite de
      durée » lancé depuis l'app passe toujours par le script partagé `hestia_charge`, jamais par
      `hestia_button_timer` (confirmé en direct : `CFG` du script contenait bien
      `{switchId,thresholdW,...}` du canal armé). Nouvelle lecture ciblée
      `ChargeScriptGenerator.evalReadConfig()`/`parseEvalResult()` (juste switchId+seuil, pas le
      reste de `CFG`) et `DeviceRepository.getActiveChargeThreshold(device)`
      ([DeviceRepository.kt](app/src/main/java/kapoue/hestia/data/repository/DeviceRepository.kt)).
    - **Cas B — déclenché par un vrai appui sur le bouton physique : fait.** Contrairement au Cas
      A, `CFG` de `hestia_button_timer` liste **tous** les canaux configurés pour un futur appui,
      armés ou non (confirmé en direct sur la Strip4 : 4 canaux dans `CFG`, un seul `armed:true`
      dans `STATE` au même indice) — `evalReadConfig()` existant (config statique) ne suffisait
      donc pas, nouvelle lecture dédiée `evalReadArmedThresholds()`/`parseArmedThresholds()` qui
      croise `CFG[i].thresholdW` et `STATE[i].armed` par indice, et
      `DeviceRepository.getActiveButtonThreshold(device)`.
    - **Branchement commun** : les deux fonctions ci-dessus sont appelées depuis
      `DashboardViewModel` **seulement** pour les canaux « Actif » sans planning ni présence ni
      décompte connu (le seul cas où ce seuil serait sinon invisible), jamais pour tous les canaux
      à chaque cycle — Cas A essayé en premier, Cas B en repli (les deux ne sont jamais vrais en
      même temps pour un canal donné). Résultat fusionné dans `TileUiState.pendingThresholdW`
      (même champ que pour un minuteur avec durée, une seule source pour `DeviceTile`). Affiché
      sur sa propre ligne, jamais concaténé au décompte — la combinaison sur une seule ligne
      (« Actif · depuis 12:34 · Coupure à 5 W ») entrait en collision avec l'interrupteur sur la
      tuile prise seule (retour David, 2026-08-22) :
      ```
      Actif · depuis 12:34
      Coupure à 5 W
      ```
    - **Trouvé au passage, fait le 2026-08-22 : un planning avec coupure sur seuil n'affichait
      que l'horaire sur la tuile.** La donnée était déjà là (`Planning.cutoffThresholdW`, relue
      systématiquement par `DeviceRepository.getPlannings` via `Script.GetCode` — fiable ici, ce
      script-là est un one-shot jamais modifié par `Eval` après son premier déploiement,
      contrairement aux superviseurs partagés) : juste jamais affichée. Pur ajout d'affichage dans
      la branche `activePlanning != null` de `DeviceTile.toVisual`, aucune nouvelle lecture réseau.

  - **Refonte des couleurs/libellés d'état — faite le 2026-08-22, en discutant de la valeur du
    seuil ci-dessus.** David a repéré une incohérence en testant : un minuteur natif en cours
    (bouton avec durée, ou Perso/Manuel) était étiqueté « Planifié » comme un vrai planning —
    alors que pour lui « Planification » ne veut dire qu'une chose : un début et une fin décidés
    **à l'avance**, jamais un minuteur lancé maintenant. Ça expliquait aussi une remarque
    précédente sur la couleur orange, perçue comme une alerte.
    - **Minuteur natif en cours reclassé « Actif »** (vert), qu'il ait une durée (décompte) ou
      pas (déjà fait juste avant, seuil sans durée) — les deux se rejoignent enfin sous le même
      libellé, cohérent avec la définition de David. Seul un vrai planning (`activePlanning`)
      garde « Planifié ».
    - **Présence et Planifié, jusque-là même couleur (orange, `timedText`), séparés en deux
      teintes distinctes** — maquettes comparées en direct avec David (Bleu/Turquoise/Violet/Rose
      pour Présence, Violet/Rose/Indigo/Ambre pour Planifié) : retenu **indigo pour Présence**
      (`presenceLed`/`presenceText`, `#378ADD`/`#0C447C` en clair) et **violet pour Planifié**
      (`plannedLed`/`plannedText`, `#7F77DD`/`#534AB7` en clair) — `StateColors.kt`
      ([StateColors.kt](app/src/main/java/kapoue/hestia/ui/theme/StateColors.kt)). Valeurs sombres
      choisies dans la même famille (bleu/violet clairs, cohérents avec le reste de la palette
      sombre déjà en place) mais pas testées en direct (David est en thème clair). **À vérifier
      que les deux teintes ne se confondent pas à l'usage réel, sur écran — David a prévenu que
      c'était son inquiétude principale avant de valider.**
    - Répercuté sur l'écran Détail (`DetailScreen.PlanningRow`, la mise en avant « En cours »
      partagée planning/présence choisit maintenant la bonne couleur selon `Planning.isPresence`).
    - **Effet de bord accepté, pas corrigé ici** : le badge d'état de l'écran Détail
      (`StatusBadge.kt`, tout en haut de l'écran) a sa propre logique plus simple, un seul état
      « Minuterie » pour tout minuteur en cours (seuil ou pas, jamais distingué de Planifié à cet
      endroit) — récupère maintenant la couleur violette de `plannedLed` par simple renommage de
      champ, sans que ce soit un choix délibéré pour cet endroit précis. Incohérent avec le
      reclassement « Actif » ci-dessus, mais écran/composant différent, pas demandé, à revoir
      séparément si ça gêne à l'usage.

- **Audit des fonctions RPC de la prise non gérées** par Hestia (mesure d'énergie détaillée,
  métriques cumulées, etc.).
- **Historique / graphique de consommation par prise** (2026-08-18) : histogramme ou courbe dans
  le temps, pour repérer visuellement des cycles réguliers (recharge mensuelle d'un scooter
  électrique, d'un Mac...). Pas trivial : l'API RPC classique n'expose qu'un compteur cumulatif
  (`aenergy.total`, jamais remis à zéro) et une fenêtre glissante très courte (`aenergy.by_minute`,
  3 valeurs seulement) — rien qui ressemble à un historique long terme côté appareil. Pour un vrai
  graphique sur plusieurs semaines/mois, il faudrait qu'Hestia échantillonne et stocke lui-même
  dans le temps (relevés périodiques de `aenergy.total`, deltas calculés) — une **nouvelle
  catégorie de donnée** pour le projet (télémétrie historique, pas de la configuration d'appareil
  ni un cache de confort comme aujourd'hui), à peser avant de s'engager : stockage qui grossit sans
  fin (politique de rétention à définir), échantillonnage qui suppose une tâche de fond régulière
  (à distinguer du principe « pas de scheduler pour piloter un appareil » — ici il s'agirait de
  *lire*, jamais d'agir), et l'appareil doit rester joignable au moment de chaque relevé sous peine
  de trous dans la courbe. À explorer : Shelly propose peut-être une fonction de journalisation
  native plus riche sur certains modèles (EM/EM1/PM1, voir plus bas « Fonctions liées au firmware
  2.0.0 ») qui simplifierait le besoin sans qu'Hestia ait à tout stocker lui-même — à vérifier
  avant de partir sur la solution la plus lourde.
- **Fonctions liées au firmware Shelly 2.0.0 — revue de la doc officielle le 2026-08-21.**
  Alarmes seuil natives sur EM/EM1/PM1 : toujours en attente (recoupe la coupure sur seuil actuelle,
  gérée par script maison — à voir si ça la simplifierait ; matériel EM/PM différent d'un Switch
  classique donc pas garanti applicable).
  **LED du PowerStrip Gen4 : résolu, confirmé par la doc et par David (Strip4 réellement en Gen4)**
  — le composant `POWERSTRIP_UI` (majuscules) et le schéma `leds.night_mode.{enable,brightness,
  active_between}` sont identiques à ce qui a été validé sur Gen3. Rien à corriger, la LED
  fonctionne déjà (vert ON / éteint OFF confirmé en usage réel).
  **Compteurs d'usage natifs du Switch (`counts.on_time`/`switch_on`/`on_above_thr` sur
  Switch.GetStatus, déjà lus mais jamais affichés) : écarté le 2026-08-21.** Seulement un cumul
  brut depuis la dernière remise à zéro, aucune notion de période (mois, coût) — l'afficher
  inviterait immanquablement des demandes qu'Hestia ne peut pas satisfaire proprement (répartition
  mensuelle, coût cumulé) sans un vrai historique horodaté, cf. l'entrée « Historique/graphique de
  consommation » ci-dessus, déjà écartée pour les mêmes raisons de fond.
  **`Script.addRpcHandler`** (permettrait à un script de s'exposer comme une vraie méthode RPC,
  `Script.MaFonction`, réponse JSON structurée au lieu de `Script.Eval` + reparsing de chaîne) :
  **repoussé le 2026-08-21**, pas maintenant — pourrait un jour remplacer `Script.Eval` dans les
  scripts superviseurs (bouton/seuil/présence), mais pas de complexité de script supplémentaire
  tant que la limite des 3 scripts simultanés reste un souvenir récent et douloureux.
  Objet `alt` dans `CheckForUpdate` (firmware alternatif, ex. Zigbee sur la Strip4) : **abandonné
  le 2026-08-21**, hors sujet pour un projet Wi-Fi local uniquement.
- **HTTPS forcée sans possibilité de désactivation sur le matériel neuf (`enhanced_security`)**
  (2026-08-21, priorité identifiée mais pas encore planifiée). Depuis le firmware 2.0.0, tout
  appareil **sorti d'usine** avec ce firmware (de plus en plus fréquent avec le temps) a HTTPS et
  la redirection HTTP→HTTPS activés en permanence, **sans aucun moyen de les désactiver** —
  contrairement à un appareil juste mis à jour vers 2.0.0 (le cas de tout le parc actuel), qui
  reste en HTTP simple par défaut, rien à changer là. Risque concret : le prochain appareil acheté,
  neuf, pourrait être **totalement injoignable par Hestia dès le déballage**, sans que rien dans
  l'app n'explique pourquoi (juste « injoignable », comme n'importe quel autre problème réseau) —
  scénario redouté : un utilisateur F-Droid qui abandonne l'app en pensant qu'elle est cassée.
  Piste technique à creuser le jour où on s'y attaque : les certificats posés par Shelly sont
  auto-signés (PKI interne à Shelly, pas une autorité reconnue) — un client HTTPS strict rejetterait
  la connexion par défaut ; il faudrait soit faire confiance à ces certificats explicitement pour
  les appels vers le réseau local (le risque MITM y est déjà limité, l'intérêt du certificat ici
  est surtout de contourner le blocage de redirection, pas d'authentifier un tiers), soit une
  logique d'épinglage au premier contact (TOFU). Décision de confiance à trancher avant de coder,
  pas juste un détail d'implémentation.
- **Trouver l'IP du hotspot directement depuis Hestia** (2026-08-14) : un bouton « Trouver l'IP »
  par champ IP, qui interroge l'admin de la prise (`192.168.33.1`) pendant qu'elle est encore en
  mode point d'accès, pour lire l'IP qu'elle vient d'obtenir sur le réseau cible. Ne fonctionne que
  dans cette fenêtre précise (téléphone connecté au Wi-Fi propre de la prise, juste après lui avoir
  donné le Wi-Fi cible) — ne résout pas le cas où la plage entière du hotspot change à chaque
  redémarrage (vécu en vacances). Utile comme petit confort, pas comme solution générale. Note :
  une bonne partie de ce besoin sera couverte par le provisioning direct (voir lot en cours
  ci-dessus, « Ajout d'un appareil neuf »), qui lit cette même IP automatiquement au passage.
- **Lien Liberapay (ou PayPal) dans À propos** (2026-08-14) : un lien de plus à côté de la licence
  GPL, aucun impact vie privée (lien ouvert à la demande de l'utilisateur). Liberapay plutôt que
  PayPal si un seul à choisir — plus dans l'esprit du projet.
- **Bluetooth en repli du Wi-Fi** (2026-08-14) : la prise expose aussi ses méthodes RPC en Bluetooth
  Low Energy (trame différente : longueur 4 octets + JSON, découpée par MTU). Le Wi-Fi resterait
  toujours le chemin par défaut ; en cas d'échec Wi-Fi, message clair « Appareil injoignable en
  Wi-Fi » avec action explicite « Essayer en Bluetooth » — jamais de bascule automatique invisible
  (le BLE suppose d'être à portée physique). Chantier réel non trivial : nouveau transport derrière
  l'abstraction driver existante, appairage BLE géré par Android, connexion avec état (contrairement
  au Wi-Fi), protocole de trame à écrire et tester, permissions BLE qui varient selon la version
  Android. **Pas encore testé de bout en bout** — à valider à la main (nRF Connect) avant d'estimer
  sérieusement l'effort. Priorité modérée : le bouton physique couvre déjà l'essentiel du cas
  « à portée mais pas de Wi-Fi » pour une prise. Recoupe partiellement le repli cloud (voir « Fait
  — pour mémoire ») : le cloud couvre déjà le cas « loin de la maison », le Bluetooth couvrirait
  plutôt « à la maison mais Wi-Fi en panne » — pas le même besoin, garder les deux en tête séparés.
- **Tags QR code** (2026-08-14) : coller un QR code physique sur un appareil (ex. un Mac) pour
  lancer directement sa programmation au scan, via un deep link Android (`hestia://tag/<uuid>`).
  Le tag encode un identifiant opaque, jamais un nom — robuste au renommage. Modèle retenu : des
  tags pré-générables en lot (utile pour une commande d'impression groupée), associables/
  réassociables/détachables à une programmation à tout moment sans réimprimer, effaçables
  définitivement en cas de perte. Génération interne avec logo au centre (bonus, un QR externe doit
  aussi fonctionner). Chantier de taille comparable à ntfy en son temps — plusieurs lots à prévoir
  (deep link, cycle de vie des tags, écran de gestion, export PDF pour impression via l'API PDF
  native Android, pas de nouvelle dépendance nécessaire).

## Fait — pour mémoire

Points sortis du backlog, avec ce qui a été tranché :

- **Cloud Shelly : interrupteur par appareil + repli à distance** (2026-08-20, testé en direct —
  allumage/extinction et lecture d'état/conso réels depuis un téléphone en 5G, Wi-Fi coupé ;
  publié en 2.3.0). Deux briques indépendantes, toutes deux opt-in et désactivées par défaut :
  un interrupteur par appareil physique (écran Modifier) pour `cloud.enable`, avec statut connecté
  et Cloud ID en lecture seule ; et un repli à distance (clé de compte + adresse serveur dans
  Réglages, stockées chiffrées, jamais journalisées) qui bascule le Tableau sur l'API Cloud
  Control de Shelly uniquement quand le réseau local échoue. Portée volontairement limitée à
  l'état/conso et au bouton allumer/éteindre : planning, présence, seuil, minuteur, LED et
  firmware restent strictement locaux, l'API cloud de Shelly n'exposant aucune passerelle RPC
  générique pour le reste. `Device.cloudId` (MAC mis en cache dès qu'un appareil répond une fois
  en local, normalisé en minuscules au point d'usage — l'API cloud est sensible à la casse) et un
  verrou d'1,1 s entre deux appels cloud (limite Shelly d'1 requête/seconde, découverte en testant
  un allumage suivi d'une extinction trop rapprochée). Picto Wifi/antenne à côté du nom indique
  d'où vient l'état affiché — jamais silencieux sur la provenance.
- **Renommer un appareil gardait l'ancien nom dans les notifs ntfy** (2026-08-19, confirmé
  corrigé). Deux causes distinctes sur deux écrans différents : `resyncDeviceName` lancé sur la
  portée du ViewModel de l'écran Modifier, tuée par la fermeture quasi immédiate de cet écran après
  l'enregistrement (corrigé par une portée applicative dédiée, `@ApplicationScope`) ; et le
  renommage d'un canal depuis Réglages, qui n'appelait cette resynchro nulle part (corrigé en
  l'ajoutant). Au passage, `STRIP_NAME_MAX_LENGTH` monté de 10 à 12 caractères (retour de test réel).
- **Traduction anglaise** (2026-08-19) : `values/strings.xml` (défaut) porte désormais l'anglais,
  `values-fr/` le français — Android choisit selon la langue système. Formats horaires rendus
  locale-aware (`formatClockTime`). Scope volontairement laissé de côté : les messages du journal
  de diagnostic restent en français uniquement (volume de points d'appel trop élevé pour l'instant) ;
  les données de démo aussi, pour ne pas casser les captures d'écran F-Droid actuelles.
- **Rouleaux crantés** (minuteur et horaires de présence/planning) : `TimeWheelPicker` maison,
  défilement infini + retour haptique, sans dépendance.
- **Relire les horaires de présence depuis l'appareil** (c'était un défaut de correction : un
  second téléphone affichait des valeurs par défaut et pouvait écraser la programmation).
  Résolu par la **piste (a)** — marqueur `// hestia_windows:[[start,end,margin],…]` en tête du
  script généré, relu via `Script.GetCode`. La configuration voyage avec le script, Hestia ne
  stocke rien.
- **Planning** via le composant `Schedule` natif, **y compris le passage par-dessus minuit**
  (l'extinction est programmée sur les jours décalés au lendemain) et l'indicateur sur la tuile.
- **Coupure automatique sur seuil de consommation** : script `hestia_charge` qui surveille
  `apower` et coupe le relais après 60 s sous le seuil, puis s'auto-désactive.
- **Puissance instantanée sur la tuile** et **marqueur « Mode présence »** sur le Tableau.
- **Notifications** : WorkManager (~15 min, délai assumé et annoncé dans l'UI), opt-in, désactivé
  par défaut, `POST_NOTIFICATIONS` demandée à l'activation seulement. Notifie les **bornes** de
  programmation (une seule notification par borne), la fin d'un minuteur et la coupure sur seuil ;
  jamais une action manuelle. **Complété le 2026-07-29** par une option **ntfy** (notifications
  instantanées, désactivée par défaut) — voir entrée dédiée plus bas ; la tension "100 % local"
  notée ici initialement est retranchée, cf. § Notifications instantanées (ntfy).
- **Écran de configuration de présence** : supprimé (la présence se gère depuis l'écran de
  détail, comme le planning), ce qui rend caduque l'ancienne demande de confirmation
  « modifications non enregistrées ». La route de navigation `presence` associée, restée morte
  dans `Destinations.kt` après cette suppression, a été retirée au lot Nettoyage (2026-07-27).
- **Vérification manuelle du firmware** : section « Mise à jour du firmware » sur l'écran de
  détail, bouton « Vérifier » qui interroge `Shelly.CheckForUpdate` — jamais en tâche de fond.
  Bêta signalée mais jamais installable. Tranché : pas de notification de mise à jour disponible
  (contrairement à ce qu'envisageait l'ancienne entrée de ce backlog) — la vérification reste
  ponctuelle, à la main de l'utilisateur. (N'est plus la seule action à sortir du réseau local
  depuis l'ajout de ntfy, opt-in — voir plus bas.)
- **Coupure sur seuil, ergonomie** : case à cocher remplacée par un interrupteur (désactivé par
  défaut, même style que Notifications), et saisie libre du seuil remplacée par une roulette sur
  des valeurs prédéfinies (5, 10, 20, 30, 40, 50 W, défaut 10) via le nouveau `ValueWheelPicker`,
  qui généralise `TimeWheelPicker` à une liste de valeurs arbitraire.
- **Redémarrage manuel de l'appareil** (dépannage) : bouton sous la section firmware
  (`Shelly.Reboot`), gated sur `DriverType.SHELLY_GEN2` plutôt qu'affiché sans condition — n'a
  pas d'équivalent générique si une autre marque est gérée un jour. Jamais bloqué par un
  minuteur en cours (peut justement servir à débloquer une prise plantée). Confirmation
  obligatoire avant déclenchement.
- **Planning « Unique »** (occurrence unique datée, plutôt que récurrente) : cron avec
  jour-du-mois/mois renseignés (au lieu de `*`) au lieu d'un bricolage à part. Nettoyage
  automatique du planning expiré à la prochaine lecture (pas de tâche de fond). Chips
  Aujourd'hui/jour précis en sélection unique, remplacent « Tous les jours » en mode Unique.
- **Coupure sur seuil pour les plannings**, Unique **et récurrent** : script dédié par planning
  (jamais partagé, ni entre eux ni avec le minuteur — un nom de script fixe aurait fait échouer
  `Script.Create` en cas de collision), démarré/arrêté par le même programme cron que
  l'allumage/extinction (`calls` de `Schedule.Create`, jusqu'à 5 appels, validé en direct). Pour
  le récurrent : validé qu'un script relancé après un `Script.Stop` réexécute proprement depuis
  le début (`let` réinitialisés), donc se réarme correctement à chaque occurrence sans recréer
  quoi que ce soit.
- **Deux réglages « Perso » nommables** (au lieu d'un seul, anonyme) : chacun avec un nom libre
  (20 caractères max), sa propre durée et coupure optionnelle. Migration Room additive (v7→v8).
- **Notifications instantanées (ntfy)** — 2026-07-29 : option opt-in, désactivée par défaut, qui
  fait notifier **l'appareil lui-même** (`HTTP.Request` vers `ntfy.sh`, jamais `HTTP.POST` qui ne
  permet pas d'en-têtes personnalisés donc pas de titre) au lieu du téléphone via WorkManager —
  supprime le délai de 0 à 15 min. Sujet ntfy stocké chiffré (Android Keystore,
  `androidx.security:security-crypto`). Un script dédié, nouveau, est déployé uniquement pour
  notifier la fin naturelle d'un minuteur Manuel/Perso **sans** coupure (seul cas sans aucun
  script associé jusque-là) ; nettoyé systématiquement avant toute extinction manuelle pour ne
  jamais notifier une action utilisateur (bug trouvé et corrigé avant la mise en usage réel).
  Bascule automatiquement l'ancien système (WorkManager) en repli si désactivé. **Rattrapage** :
  un appareil injoignable au moment du bascule est resynchronisé silencieusement dès que le
  Tableau le recontacte (déjà interrogé en boucle toutes les 5 s), via une génération comparée
  en préférences — pas de tâche de fond dédiée. Revu et corrigé les textes vie privée (À propos,
  fiche F-Droid, README, `CLAUDE.md`) qui promettaient encore « rien ne quitte jamais le réseau
  local » pour refléter cette exception explicite et opt-in.
- **Barre de statut système illisible en thème clair** (icônes blanches sur fond blanc) :
  `enableEdgeToEdge()` ne fixait la couleur des icônes qu'au démarrage selon le thème système,
  sans suivre le réglage propre à l'app (Réglages → Apparence) ni ses changements en direct.
  Recalée à chaque changement de thème effectif via `WindowInsetsControllerCompat`.
- **Minuteur déclenché par le bouton physique** : appui sur le bouton = arme un minuteur (durée +
  coupure sur seuil optionnelle), configurable comme un réglage Perso mais sans passer par
  l'appli. Placé à côté de la section Firmware (« c'est du matos »). Appui long/double impossible
  sur ce matériel (pas de composant Input adressable en mode bouton natif, vérifié en direct).
  Sans réglage, le bouton retrouve le comportement natif de la prise (simple on/off).
- **Bascule automatique entre deux adresses IP par appareil** (2026-08-14) : chaque appareil peut
  avoir un 2ᵉ emplacement IP nommé (ex. « Domicile » / « Vacances »), Hestia retente
  automatiquement l'autre en cas d'échec réseau sur le premier et mémorise lequel a répondu en
  dernier pour l'essayer en priorité au prochain appel. Toujours au moins une IP configurée
  (poubelle masquée sur le dernier emplacement restant). L'IP réellement utilisée est maintenant
  affichée dynamiquement (Réglages et fiche Détail), plus jamais figée sur le 1ᵉʳ emplacement.
- **Corrections diverses signalées à l'usage réel** (2026-08-14, remontées après un séjour avec la
  prise sur un hotspot mobile) : journal de diagnostic réduit à 250 lignes (2000 auparavant, plus
  de sens à ce stade) ; texte du bouton physique simplifié, explique désormais le comportement par
  défaut sans réglage ; renommer un appareil redéploie automatiquement les scripts qui contiennent
  son nom (bouton, présence, planning) — le nom était écrit en dur au déploiement, jamais relu par
  la prise ensuite ; notification de fin manquante pour un minuteur Perso/Manuel à seuil qui va au
  bout de sa durée sans jamais couper (le seul cas resté silencieux) ; bouton « Partager »
  (partage système Android, texte + lien F-Droid) ajouté dans À propos, à côté du QR code.
- **Publication F-Droid 1.1.0** : tag Git pointant le commit de version, fiche de référence
  (`fdroid/kapoue.hestia.yml`) mise à jour avec le nouveau build et les catégories `Connectivity` +
  `Remote Controller` (seconde catégorie demandée en relecture). Accès en écriture au fork GitLab
  de `fdroiddata` vérifié (`git push --dry-run`), MR à pousser réellement à la prochaine
  publication groupée avec les lots en cours.
- **Pause de planning** (2026-08-14) : bouton pause à côté de la poubelle sur un planning actif
  (avertit si en cours, comme la suppression). Supprime réellement les programmes de l'appareil,
  mémorise horaires/jours/seuil en local (nouvelle table `paused_plannings`) pour recréer à
  l'identique à la réactivation — avec les mêmes contrôles de conflit qu'une création normale.
  Un planning en pause peut aussi être oublié définitivement sans jamais le réactiver.
- **Minuteur sans limite de durée** (2026-08-14, remplace l'idée initiale de « Détection de fin de
  charge » en réutilisant l'existant plutôt qu'un nouveau système) : un switch dans le sélecteur de
  durée retire la limite de temps, coupure sur seuil seule (alors obligatoire), avec une grâce fixe
  de 15 min avant toute surveillance — absente sinon, pour ne pas changer le comportement déjà en
  place. S'applique aux 2 réglages Perso, au minuteur Manuel et au bouton physique. Limite connue :
  la tuile du Tableau n'affiche ni compte à rebours ni seuil pendant une charge sans limite (pas de
  minuteur natif auquel accrocher ces indicateurs).
- **Regroupement des appareils multi-canaux** (2026-08-14) : chantier le plus large du lot, en
  plusieurs passes après retours d'usage réel (Strip 4).
  - **Nom d'appareil stable** (`Device.deviceName`, migration v11→v12) : enregistré une fois à
    l'ajout, identique sur tous les canaux d'un même appareil, jamais affecté par le renommage
    d'un canal individuel (ex. « Frigo »). Corrige un vrai défaut trouvé en cours de route : deviner
    ce nom en inspectant les canaux se cassait dès qu'on personnalisait le premier d'entre eux.
  - **Tableau** : canaux d'un même appareil physique (même IP) toujours groupés côte à côte à
    l'affichage (jamais mélangés avec un autre appareil), avec un en-tête (picto + nom d'appareil)
    au-dessus. Numéros `01/02…` sur les tuiles retirés (n'apportaient plus rien une fois l'ordre
    corrigé). Nouveau séparateur `·` pour les noms de canaux par défaut (« Nom · 1 »), cohérent avec
    celui déjà utilisé dans Réglages ; les noms déjà enregistrés avec l'ancien format (espace simple)
    sont corrigés automatiquement au lancement, sans action de l'utilisateur.
  - **Réglages** : même ordre groupé qu'au Tableau (calcul partagé, `DeviceRepository.
    groupedForDisplay`), un bloc multi-canaux devient un en-tête (nom d'appareil + IP partagée +
    monter/descendre une seule fois pour tout le groupe + menu Modifier/Ouvrir/Supprimer, même
    présentation qu'une prise seule) suivi d'une ligne par canal (nom propre, crayon pour le
    renommer, poubelle pour le retirer seul). Modifier l'IP ou le type depuis l'en-tête s'applique
    à tous les canaux d'un coup — évite qu'un seul canal se retrouve avec une IP différente des
    autres (ils partagent la même prise physique). Point non tranché, à surveiller à l'usage : le
    type (Prise/Lampe/Capteur) est uniformisé sur tout le bloc par ce même écran, pas réglable par
    canal.
  - **Deux bugs multi-canaux trouvés en direct sur Strip 4, corrigés (2026-08-15)** :
    - **Collision de nom de script entre canaux** : le moteur de scripts Shelly est partagé par
      tout l'appareil physique (pas un par canal) — les quatre générateurs (`ButtonTimer`,
      `Charge`, `Presence`, `TimerNotify`) utilisaient un nom de script fixe, retrouvé/écrasé
      d'un canal à l'autre du même appareil au lieu d'être propre à chacun. Diagnostiqué via
      `Script.List`/`Script.GetCode` en direct (deux scripts distincts ciblant le même canal).
      Corrigé : nom de script désormais suffixé par le canal (`hestia_xxx_<switchId>`) partout,
      y compris dans `DeviceRepository` (tous les points d'appel).
    - **Bouton physique du bloc prise inopérant** : `Switch.GetStatus.source` vaut `"button"` sur
      Plug M Gen3 mais `"short_push"` sur Strip 4 — le script de minuteur bouton ne s'armait
      donc jamais sur ce second modèle (programmation posée, mais ni durée ni seuil n'agissaient).
      Corrigé par un helper `isButtonSource(src)` acceptant les deux valeurs confirmées en direct,
      plus `long_push`/`double_push`/`triple_push` par prudence (non vérifiées sur du matériel réel).
  - Spinner de rafraîchissement automatique du Tableau (toutes les 5 s) : ne doit plus jamais
    apparaître hors tirage manuel, quelle que soit la durée du cycle — l'indicateur temporisé
    précédent (déclenché après 600 ms si un appareil traînait) est retiré.
- **Refonte du Tableau, lot 1/5 — permission réseau en message global** (2026-08-17) : retiré
  l'état « Permission requise » par tuile (`TileStatus.PermissionRequired` supprimé du modèle
  partagé, tuiles concernées basculées en `Offline`), remplacé par un bandeau unique en tête du
  Tableau quand la permission manque, avec la même action que la section Réglages (ouvre
  directement les réglages système, ne redemande jamais la permission depuis l'app). Écran Détail
  aligné sur le même principe (repli sur l'état « Hors ligne », pas de message dédié — il n'est
  accessible qu'après être passé par le Tableau, où le bandeau est déjà visible). Prélude à la
  refonte visuelle complète des tuiles (lots 2 à 5 : anatomie de la tuile solo, bloc multi-canaux
  en ligne avec modale, bouton lié au minuteur physique, mise en avant du programme en cours).
- **Refonte du Tableau, lot 2/5 — anatomie de la tuile solo** (2026-08-17) : cercle inspiré de la
  vraie prise (deux trous), fond de tuile teinté par état (clair + sombre, nouvelles valeurs
  `StateColorSet.*Bg`), bouton rond neutre séparé de l'état, consommation en bas à gauche, retrait
  du picto de type d'appareil sur la tuile, retrait du bouton « Réessayer » (le rafraîchissement
  auto 5 s + le tirage manuel suffisent). Deux couleurs distinctes et volontairement indépendantes :
  l'anneau du cercle reflète le fait physique (courant ou non), le fond/texte reflète le régime
  (actif seul / piloté par un programme / éteint / indisponible) — ex. une présence en pause reste
  « Planifié » en orange même si l'anneau est gris à cet instant. Libellés simplifiés : « Planifié »
  unifie minuteur/présence/planning sur la tuile (l'écran Détail garde ses libellés précis, via
  `StatusBadge`, non touché) ; « Éteint » remplace « Repos » ; « Indisponible » remplace
  « Hors ligne » partout. Point laissé en suspens : le petit picto de type au-dessus de la tuile
  (en-tête de groupe) n'a pas été retiré, jamais explicitement inclus dans les échanges sur la
  tuile elle-même — à trancher.
- **Refonte du Tableau, lot 3/5 — bloc multi-canaux en ligne** (2026-08-17) : la grille 2×2 par
  canal est remplacée par une seule ligne de petits cercles (`DeviceStripRow`), façon vraie
  multiprise. Chaque cercle reprend l'anneau/les trous du cercle solo, avec nom (10 caractères
  max, tronqué sans « … ») et état sur deux lignes en dessous — jamais de couleur seule, même à
  cette échelle. Pas de bouton ON/OFF direct sur le Tableau pour un canal de bloc (contrairement à
  une prise seule) : le tap ouvre une modale (`ChannelQuickSheet`, bottom sheet) avec conso,
  interrupteur, état complet, et un lien « Voir le détail » vers l'écran complet (plannings,
  présence, seuils — hors du périmètre de la modale).
- **Refonte du Tableau, lot 4/5 — bouton app relié au minuteur physique** (2026-08-17) : allumer
  depuis le bouton rond de l'app (tuile solo ou modale du bloc) relit d'abord le minuteur configuré
  pour le bouton physique de l'appareil et reproduit exactement son comportement — durée seule,
  durée + seuil, ou seuil sans limite de durée — via les fonctions déjà existantes (`startTimer`/
  `startChargeTimer`/`startUnlimitedChargeTimer`, réutilisées telles quelles, aucune nouvelle
  fonction repository). Sans minuteur bouton configuré, allumage classique inchangé. Éteindre reste
  toujours un `Switch.Set` direct, jamais concerné, quel que soit ce qui a déclenché l'allumage.
  Limite connue et assumée : le script du minuteur bouton distingue une annulation volontaire d'une
  fin naturelle via le champ `source`, détecté par le matériel — un allumage déclenché depuis l'app
  ne peut pas se faire passer pour un appui physique ; seule la formulation d'une notification de
  fin pourrait en être affectée, jamais l'action elle-même (coupure toujours effective).
- **Correctif couleur de fond, retour de test réel** (2026-08-17) : le fond de la tuile (et des
  petits cercles du bloc) suit désormais **toujours** le fait physique (vert si le courant passe,
  gris sinon), même quand un programme est en cours — plus de fond orange pour « Planifié »,
  jugé source de confusion en usage réel sur le Strip 4. La nuance « un programme pilote la
  prise » reste lisible, mais uniquement dans le texte (« Planifié » en orange), jamais dans le
  fond. `StateColorSet.timedBg` retiré (devenu inutilisé).
- **Refonte du Tableau, lots 1 à 4/5** (2026-08-17) : permission en message global, tuile solo
  façon vraie prise, bloc multi-canaux en ligne + modale, bouton app lié au minuteur physique —
  livrés et validés en test réel sur Pixel/Sony, Plug M et Strip 4. **Le lot 5/5 (mise en avant du
  programme en cours) est codé mais pas encore testé** — voir § En cours de traitement, gardé en
  l'état intentionnellement pour l'instant.
- **LED d'état par appareil** (2026-08-15) : interrupteur dans la boîte « Modifier » (solo
  et bloc), un seul réglage par appareil physique. **ON** = 100 % le jour, réduite à 30 % de 22h à
  8h ; **OFF** = éteinte en permanence. Entièrement natif au firmware (`night_mode` du composant
  `plugs_ui`/`powerstrip_ui`), réutilisé pour les deux états (fenêtre 22h-8h à 30 % pour ON, fenêtre
  toute la journée à 0 % pour OFF) — aucun script, aucune configuration stockée par Hestia. Deux
  pièges confirmés en direct sur le Strip 4 et le Plug M avant d'écrire le code : le nom du
  composant diffère selon le modèle (solo vs bloc), **et sa casse aussi** (`plugs_ui` en
  minuscules, `POWERSTRIP_UI` en majuscules) — `DeviceRepository.getLedState`/`setLedState`
  essaient les combinaisons plausibles plutôt que d'en figer une.
- **Build F-Droid cassé par R8, trouvé et corrigé** (2026-08-15) : le build release de la 2.0.0
  a échoué sur l'infrastructure F-Droid (CI `checkupdates-bot-fdroiddata`) — R8 refusait de
  continuer sur 4 classes manquantes de `com.google.errorprone.annotations`, référencées par
  Google Tink (dépendance interne de `androidx.security:security-crypto`, utilisée pour chiffrer
  le sujet ntfy). Jamais vu en local car seul `assembleDebug` avait été testé — R8 ne tourne que
  sur la variante `release`. Corrigé par une règle `-dontwarn` dans `proguard-rules.pro` (annotations
  de compilation uniquement, jamais utilisées à l'exécution), validé ensuite par un vrai
  `assembleRelease` local réussi. **Conséquence** : les tags 2.0.0 et 2.1.0 resteront cassés sur
  F-Droid (commit figé, non corrigeable rétroactivement) — la 2.2.0 est la première version à
  intégrer le correctif. Signalée par un mainteneur F-Droid via une issue Codeberg le jour même,
  réponse technique postée avec lien vers le correctif.
- **Canal de communication Mastodon + Telegram** (2026-08-15) : finalement un **compte
  dédié par application** plutôt qu'un compte unique pour tout le portefeuille (décision prise en
  cours de route) — `@hestia_app@mastodon.social` et canal public Telegram `Hestia_app` (posté via
  le bot `@hestia_app_bot`), même modèle prévu pour les autres applis (Agora, MainTask, Kartapuss,
  Ignis). Création des comptes forcément manuelle (David) — Claude Code ne crée jamais de compte.
  Section « Contact » ajoutée à l'À propos (FR/EN) avec liens vers les deux. Publication
  automatisée en place (jetons API dans `~/.hestia-social.env`, hors dépôt), toujours avec
  confirmation explicite avant chaque envoi réel — jamais silencieux. Changelogs F-Droid par
  version rédigés en parallèle (`fastlane/metadata/android/*/changelogs/<versionCode>.txt`).
