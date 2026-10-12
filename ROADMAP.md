# Roadmap

Problèmes connus, optimisations et évolutions prévues. Le fonctionnement actuel
est décrit dans [ARCHITECTURE.md](ARCHITECTURE.md).

Rappel : toute évolution des apps arrive sur **Android et iOS dans le même commit
Sofia** (voir [README.md](README.md#la-règle--un-commit-sofia--android-et-ios-au-même-stade)).

---

## Corrigé récemment (octobre 2026)

### Collecte et stockage

- **B1610 lent et incomplet.** `fetch_next_b1610` téléchargeait 6 jours SP par SP
  (jusqu'à 300 requêtes avec 0,5 s de pause, soit plus de 3 min par exécution, toutes
  les 15 min). Il ne sauvegardait que le **dernier** jour de la boucle (J+2 après le
  dernier point en base). Résultat : la base sautait un jour sur deux.
  En production, les journées du 11/09 au 03/10/2026 étaient vides un jour sur deux (12 jours manquants).
  Désormais : une requête `/stream` couvrant (dernier point − 3 jours) → maintenant,
  et une resynchronisation complète au démarrage (vérifiée sur une copie de la base
  de prod : 12 trous → 0, 45 120 lignes).
- **PN** : même passage à `/stream` (avant : jusqu'à 100 requêtes par exécution).
- **Écritures en base** : un commit par ligne (8,4 ms/ligne, soit ~6 min pour
  resynchroniser 45 000 lignes B1610 sur PC, bien plus sur le Pi) remplacé par un
  upsert en lot (3 s).
- **`/pn/top-production`** renvoyait `max_mw: 0` avec les dates B1610 (import
  écrasé dans `crud/__init__.py`).
- **`/remit/{id}`** répondait toujours 404 (recherche par clé primaire alors que
  la clé est `mrid`).
- **Gzip** sur les réponses de l'API : transferts divisés par 12 à 24.
- **Dates `from`/`to` Elexon** en UTC explicite : les SP 1-2 du premier jour ne
  sont plus perdus en heure d'été.
- **Segments PN de moins de 30 min** (ex. 06/09/2026, SP 36 de `T_SOFOW-21` :
  16:30-16:34 puis 16:34-17:00) : ils étaient stockés comme des lignes séparées et
  les apps dessinaient des points partiels. Le serveur garde maintenant une valeur
  par SP, la moyenne pondérée par la durée.
- **Colonnes déduites supprimées** : `time_to`, `settlement_period` et `source`
  étaient toujours cohérents avec `time_from` (0 écart sur toute la base). Ils ne
  sont plus stockés, l'API les recalcule (réponses identiques pour les apps
  installées). Base de prod : 17 Mo → 4,8 Mo.
- **Somme Sofia calculée à la réception des données** (`pn_total`, `b1610_total`,
  endpoints `/pn/total` et `/b1610/total`) : les apps font une requête au lieu de
  quatre. Avec le gzip, un rafraîchissement passe d'environ 10 Mo à 164 ko.
- **Vent à 120 m partout** : l'historique (archive, 100 m) et la prévision (120 m)
  ne mesuraient pas à la même hauteur. L'écart de hauteur est faible (~2 %), mais
  les deux sources donnaient des valeurs différentes pour les mêmes heures.
  L'historique vient maintenant de l'API « historical forecast », à 120 m, identique
  à la prévision sur les heures communes (commit serveur `Use wind speed at 120 m…`,
  réversible seul).
- **Bornes de temps de l'API** converties en UTC : un `time_from` en `+02:00`
  renvoyait des données décalées de 2 h (les apps envoient du `Z`, pas touchées).

### Affichage
- **Vent dans les apps** : les valeurs étaient converties en m/s mais l'axe
  indiquait « km/h ». L'axe indique maintenant m/s (Android et iOS).
- **Anciens widgets Android** (petit, moyen) : heures en UTC, alors que tout le
  reste est à l'heure de l'appareil. Corrigé.
- **`/visual/b1610`** traçait des MWh par demi-heure sous un axe « MW » (valeurs
  divisées par 2) avec le titre de la page PN. Corrigé ; les axes indiquent UTC.

### Outils
- **Page qualité** `/visual/quality` (et `/quality` en JSON) : couverture et retard
  de chaque série, complétude par jour, trous, anomalies.
- **Déploiement** : sauvegarde de `sofia.db` avant chaque mise à jour, puis
  vérification que l'API répond (avec les logs et la commande de retour arrière
  en cas d'échec).

---

## Tests : à reprendre

**Les tests du projet ne sont pas fiables : leurs résultats ne reflètent pas la
réalité. On résoudra ce problème plus tard.** D'ici là, un test vert ne prouve
rien et un test rouge peut être obsolète.

Constat :

- **Serveur** (`test/test_db.py`, `test/test_visual.py`) : SQLite en mémoire avec
  quelques lignes écrites à la main. Ils testent les fonctions CRUD unitaires,
  pas le chemin réellement utilisé (fetch Elexon → upsert en lot → API). Aucun test
  ne couvre le scheduler ni les fetchers : la perte d'un jour B1610 sur deux est
  passée inaperçue. Trois tests échouaient avant la correction d'octobre 2026 : ils
  signalaient de vrais bugs.
- **Android** : seulement les fichiers d'exemple générés (`ExampleUnitTest`,
  `ExampleInstrumentedTest`).
- **iOS** : `SofiaCoreTests` vérifie le parsing des dates, l'agrégation et les
  records sur des données fabriquées. Rien sur les repositories, le cache, les
  widgets ni l'interface.
- Rien ne tourne automatiquement (pas de CI).

Pistes pour plus tard :

- des réponses Elexon réelles enregistrées comme fixtures, et des tests des jobs
  du scheduler avec httpx simulé (`respx`) ;
- un contrôle sur une copie de la base de prod : pas de trou, retard B1610 normal ;
- des tests des repositories Android/iOS avec un faux serveur, et la vérification
  que les deux plateformes donnent les mêmes séries agrégées ;
- une CI GitHub Actions dans chaque sous-module.

---

## Problèmes connus

### Serveur

- **Démarrage bloquant.** Le backfill (PN, vent, REMIT, B1610) est attendu avant
  de servir la moindre requête : ~20 s mesurées sur PC (surtout du réseau), plus sur
  le Pi. On pourrait le lancer en tâche de fond et servir la base existante tout de suite.
- **Écritures synchrones dans les jobs async** : l'upsert en lot bloque la boucle
  d'événements le temps de l'écriture (~3 s pour la resynchronisation B1610 complète
  sur PC). Si ça devient gênant : `asyncio.to_thread`.
- **Resynchronisation B1610 complète au démarrage** : quelques secondes
  aujourd'hui. Quand l'historique aura grossi : reprendre à (dernier point − 30 jours)
  et détecter les trous à part.
- **Latence Elexon variable** : la même requête a pris de 0,2 s à 6 s le
  12/10/2026. Les requêtes `/stream` ont un timeout de 60 s.
- **Pas d'alerte.** La page `/visual/quality` montre les trous et les retards,
  mais il faut aller la voir. Brancher `/quality` sur une alerte (mail, push) quand
  une série a un trou ou un retard anormal.
- **Avis REMIT jamais clos.** 12 avis sont encore `Active` en base alors que leur
  date de fin est passée : la tâche REMIT ne télécharge que les avis actifs, donc une
  ligne n'est jamais mise à jour une fois l'avis sorti de la liste. Les apps ne sont
  pas touchées (`/remit/active` filtre sur la date de fin).
- **Données Elexon à surveiller** (visibles sur la page qualité) : des imports
  B1610 jusqu'à −134 MW sur `T_SOFOW-11` (18/08/2026), 65 épisodes où PN et
  B1610 diffèrent de plus de 100 MW par unité, 16 avis REMIT dont la capacité
  normale ≠ disponible + indisponible.
- **Temps de réponse** : sur PC, servir tout l'historique d'une série prend 1 à
  2 s (validation Pydantic de ~9 000 objets puis JSON) ; plus sur le Pi. La
  synchronisation incrémentale (plus bas) réglerait ça côté apps.

### Apps

- Tout l'historique est re-téléchargé toutes les 60 s et le cache est
  entièrement réécrit (voir [Optimiser les données](#optimiser-les-données)).
- `/remit/active/{bmu}` est appelé une fois par unité (4 requêtes).
- Les apps de cette version appellent `/pn/total` et `/b1610/total` : **déployer
  le serveur avant de publier les apps**.
- iOS : les changements d'octobre 2026 n'ont pas pu être compilés sur la machine
  qui les a écrits (pas de Swift sous Windows). Les compiler dans Xcode avant de
  publier.
- Room est en version 1 sans migration : tout changement de schéma du cache
  demandera une migration (ou `fallbackToDestructiveMigration`, acceptable pour un cache).

---

## Optimiser les données

### Constat

- **Volume** : ~550 lignes par jour (sommes comprises), soit ~9 Mo par an avec le
  schéma v3. C'est très peu pour SQLite, même sur un Raspberry Pi.
- **Coût réel** : le transfert vers les apps. Chaque rafraîchissement de l'écran
  Graph re-télécharge tout l'historique toutes les 60 s : 3 Mo de JSON (164 ko en
  gzip) depuis les sommes serveur, contre ~10 Mo avant, puis l'app réécrit tout son
  cache.

### Par ordre d'intérêt

1. **Gzip** (fait) : transferts divisés par 12 à 24, sans toucher aux apps.

2. **Synchronisation incrémentale** (serveur inchangé, apps à modifier) :

   - demander seulement à partir du dernier point en cache, moins une marge
     (~1 jour pour PN, ~10 jours pour B1610 qui est révisé) ;
   - fusionner (upsert) dans le cache au lieu de tout effacer. Côté Room, il faut
     une clé unique (`isTestMode`, `timeFrom`) et donc une migration v1 → v2 ;
     côté iOS, une fusion dans `SofiaLocalStore`.

   Gain : de quelques Mo à quelques ko par rafraîchissement.

3. **Agrégation côté serveur** (fait : calculée dès la réception des données
   Elexon, avec migration de la base) : tables `pn_total` et
   `b1610_total`, endpoints `/pn/total` et `/b1610/total`, utilisés par les deux apps.
   Reste possible : renvoyer le B1610 directement en MW et un format JSON plus léger
   (sans `time_to`, `settlement_period`, `source`) dans une future version de l'API.

4. **Cache HTTP et fréquence** :

   - `ETag` / `Last-Modified` avec réponse `304` quand rien n'a changé ;
   - surtout, moins de polling. Le PN change toutes les 30 min, le B1610 une fois
     par jour : un rafraîchissement toutes les 5 min, ou calé sur :00/:30, plus un
     au retour au premier plan, suffit.

5. **Schéma plus compact** (pas nécessaire) :

   - Fait (schéma v2) : suppression des colonnes déduites (`time_to`,
     `settlement_period`, `source`) et des index redondants, tables `WITHOUT ROWID`.
     17 Mo → 4,8 Mo.
   - Pas fait, pas utile aux volumes actuels : dates en entier (epoch) au lieu de
     texte, BMU en petit entier (2,6 Mo mesurés).

6. **Suppression** : rien à supprimer, toutes les données servent (fenêtre
   « Tout », records). `remit.raw_json` duplique les champs déjà extraits mais ne pèse
   que 0,17 Mo. `T_HEYM11` représente 20 % des lignes mais sert au mode test. Si un
   jour l'historique devient trop lourd pour les apps, mieux vaut sous-échantillonner
   les vieilles données (moyennes horaires ou journalières) pour les longues fenêtres
   que les supprimer.

7. **Autre base de données** : pas justifié.

   - PostgreSQL, TimescaleDB ou InfluxDB se justifient pour des millions de lignes
     par jour, plusieurs écrivains ou des requêtes temporelles lourdes. Ici : ~500
     lignes par jour, un seul écrivain, quelques lecteurs.
   - SQLite reste le bon outil sur un Pi : pas de service en plus, et une sauvegarde
     se fait en copiant un fichier.
   - Réglages possibles : `PRAGMA journal_mode=WAL` (les lectures ne bloquent plus
     pendant une écriture) et `synchronous=NORMAL`.

---

## Évolutions des apps

Reprise de l'ancien `Sofia_Android/IMPROVEMENT.md`, mise à jour et valable pour
les deux plateformes.

### Architecture

- Injection de dépendances : Hilt sur Android (modules réseau et repositories,
  `@Inject` dans les ViewModels), conteneur équivalent sur iOS. But : remplacer
  l'instanciation manuelle et pouvoir injecter de fausses données en test.
- Couche domaine / use cases (`GetAggregatedProductionUseCase`,
  `GetActiveRemitsUseCase`…) : sortir l'agrégation et le calcul des records des
  repositories Android. Sur iOS, `Aggregation.swift` isole déjà cette logique.
- Navigation typée (routes en sealed class / enum), identifiant d'avis REMIT passé
  en argument, liens profonds vers un avis.

### Données

- Cache hors ligne : **fait** (Room sur Android, fichiers JSON sur iOS).
- Synchronisation incrémentale et agrégation serveur : voir
  [Optimiser les données](#optimiser-les-données).
- Synchronisation en arrière-plan (WorkManager / `BGAppRefreshTask`) et
  notification locale lors d'un nouvel avis REMIT à fort impact.
- Gzip : fait côté serveur. ETag : à faire.

### Interface

- Appui long sur le graphique : ligne verticale et bulle avec les valeurs exactes
  (MW, vent) à cet instant.
- Format d'axe adaptatif : heures quand on est zoomé sur quelques points, dates
  sinon.
- Légende placée avant le graphique.
- Point sur la carte du parc (onglet Sofia).
- Chargement en squelette (shimmer) à la place de l'indicateur circulaire.
- Transitions animées entre jeux de données.
- Accessibilité : descriptions TalkBack / VoiceOver des icônes et graphiques,
  taille de texte dynamique, dernières chaînes en dur à déplacer dans les
  ressources.
- Couleurs dynamiques Material You (Android 12+) et séries du graphique bien
  contrastées en thème sombre.

### Qualité et suivi

- Tests : voir [Tests : à reprendre](#tests--à-reprendre). Ensuite, des tests
  d'instantanés d'interface (Paparazzi, swift-snapshot-testing).
- Suivi des crashs et des performances (Crashlytics ou équivalent), dont la
  latence de l'API.
