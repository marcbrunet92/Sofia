# Architecture

Ce document décrit le système tel qu'il est : d'où viennent les données, comment
le serveur les stocke et les sert, et comment les apps Android et iOS les
téléchargent, les agrègent et les gardent hors ligne. Les problèmes connus et les
pistes d'amélioration sont dans [ROADMAP.md](ROADMAP.md).

```
 Elexon Insights API ─┐                         ┌─> App Android (Room)    ─> écrans + widgets Glance
 (PN, B1610, REMIT)   │   Sofia_Server          │
                      ├─> scheduler ─> SQLite ──┤  API REST (gzip)
 Open-Meteo (vent) ───┘   (Raspberry Pi)        │  https://sofia.lemarc.fr
                                                └─> App iOS (fichiers JSON) ─> écrans + widgets WidgetKit
```

---

## 1. Les données

Unités (BMU) suivies : `T_SOFOW-11`, `T_SOFOW-12`, `T_SOFOW-21`, `T_SOFOW-22`
(les quatre unités de Sofia), plus `T_HEYM11` (Heysham, utilisée par le **mode
test** des apps). Historique depuis le 1er avril 2026.

| Jeu | Source | Contenu | Pas | Disponibilité | Table | Endpoint |
| --- | --- | --- | --- | --- | --- | --- |
| **PN** (Physical Notification) | Elexon `PN` | production **annoncée** par l'exploitant, en MW | 30 min (Elexon envoie parfois plusieurs segments dans une demi-heure : le serveur garde leur moyenne pondérée par la durée) | publiée jusqu'à ~1 h à l'avance (gate closure) | `pn` | `/pn/{bmu}` |
| **B1610** | Elexon `B1610` | production **réelle mesurée**, en MWh par demi-heure (×2 = MW moyens) | 30 min | **~6-7 jours de retard**, puis révisée par les settlement runs suivants (II, SF, R1…) | `b1610` | `/b1610/{bmu}` |
| **Vent** | Open-Meteo (historique des prévisions + prévision) | vitesse du vent **à 120 m**, en km/h, au point 55.21 N, 2.33 E | 1 h | historique + 3 jours de prévision | `weather` | `/weather` |
| **REMIT** | Elexon REMIT | avis d'indisponibilité (capacité normale / disponible / indisponible, cause, profil) | événement | temps réel | `remit` | `/remit/...` |

Un *settlement period* (SP) est une demi-heure numérotée de 1 à 48 (46 ou 50
les jours de changement d'heure) dans le *settlement day* de Londres : en heure
d'été, le SP 1 commence à 23:00 UTC la veille. Les SP sont toujours alignés sur
:00 et :30 UTC.

### Fuseaux horaires, de bout en bout

Vérifié sur tout l'historique (octobre 2026) : 0 écart entre les sources, la base
et l'API.

| Étape | Format | Fuseau |
| --- | --- | --- |
| Elexon PN | `timeFrom` / `timeTo` en `…Z` | UTC |
| Elexon B1610 | `halfHourEndTime` sans fuseau (fin de la demi-heure) | UTC (cohérent avec `settlementDate` + `settlementPeriod`) |
| Open-Meteo | `time` sans fuseau, demandé avec `timezone=GMT` | UTC (le serveur refuse tout autre fuseau) |
| Base SQLite | texte `YYYY-MM-DD HH:MM:SS.ffffff`, début de la période | UTC sans fuseau |
| Paramètres `time_from` / `time_to` de l'API | ISO 8601, `Z` ou décalage | convertis en UTC avant la requête |
| Réponses de l'API | `"2026-10-12T00:00:00"` (sans fuseau) | UTC |
| Apps (lecture) | `parseApiUtc` / `ApiTime.parseUtc` | UTC |
| Apps (affichage) | graphiques, jauge, REMIT, widgets | **fuseau de l'appareil** |
| Pages `/visual/*` | axes et tableaux | UTC (indiqué sur la page) |

Un point est toujours positionné à son **début** (`time_from`) : la demi-heure
21:00-21:30 UTC est affichée à 23:00 sur un téléphone réglé sur Paris en été.
La jauge montre le PN de la demi-heure en cours ; les apps demandent les
données jusqu'à « maintenant », donc pas les PN futurs ni la prévision de vent.

---

## 2. Serveur (`Sofia_Server/`)

FastAPI + SQLModel/SQLAlchemy sur SQLite, APScheduler, httpx. Lancé par
`uvicorn agsi:app` (systemd `sofia.service`) derrière nginx sur le Raspberry Pi.

| Fichier | Rôle |
| --- | --- |
| `agsi.py` | point d'entrée : logging + `create_app()` |
| `app/__init__.py` | application FastAPI, gzip, cycle de vie (init DB → backfill → scheduler) |
| `app/scheduler.py` | backfill au démarrage, tâches périodiques, sauvegarde en base |
| `app/fetch/stream.py` | requêtes `/datasets/{X}/stream` Elexon (plages UTC, découpage par 30 jours) |
| `app/fetch/pn.py`, `b1610.py`, `remit.py`, `weather.py` | téléchargement et conversion de chaque source |
| `app/db/models.py`, `schemas.py` | tables SQL et schémas Pydantic de l'API |
| `app/db/crud/*.py` | upserts (unitaires et en lot) et requêtes |
| `app/api/routers/*.py` | endpoints REST et pages de visualisation matplotlib (`/visual/...`) |
| `app/quality.py` | rapport qualité : couverture, trous, retards, anomalies (`/quality`, `/visual/quality`) |
| `app/utils.py` | constantes, conversions UTC, numéro de settlement period |
| `.github/workflows/deploy.yml` | déploiement manuel : sauvegarde de la base, `git reset --hard origin/master`, redémarrage, vérification que l'API répond |

### Collecte

**Au démarrage** (`run_backfill`, attendu avant d'accepter des requêtes) :

| Jeu | Plage téléchargée |
| --- | --- |
| PN | depuis le dernier point en base − 2 jours (ou le 1er avril si la base est vide) jusqu'à J+1 |
| Vent | historique des prévisions à 120 m depuis le 1er avril |
| REMIT | avis actifs de chaque BMU, avec le détail de chaque avis |
| B1610 | **resynchronisation complète** depuis le 1er avril : quelques requêtes, comble les trous et récupère les révisions |

**Tâches périodiques** (UTC) :

| Tâche | Quand | Plage |
| --- | --- | --- |
| `fetch_next_pn` | :00, :15, :30, :45 | dernier PN en base − 1 jour → maintenant + 1 jour |
| `fetch_next_b1610` | :00, :15, :30, :45 | dernier B1610 en base − 3 jours → maintenant |
| `job_fetch_remit` | :00 | avis actifs |
| `job_fetch_weather` | :05 | 7 jours passés + 3 jours de prévision |

PN et B1610 passent par l'endpoint **`/stream`** d'Elexon : une requête couvre
une plage de temps entière pour toutes les BMU. Les bornes `from`/`to` sont des
datetimes UTC explicites (`2026-10-01T23:00Z`). Une date seule est comprise comme
00:00 UTC et ferait perdre les SP 1-2 en heure d'été.

### Stockage

Base SQLite `sofia.db` (ignorée par git), schéma v3 (`PRAGMA user_version = 3`).
Sur la base de production du 12/10/2026 : **4,8 Mo** (17 Mo avec le schéma v1).

| Table | Colonnes | Clé primaire | Lignes (12/10/2026) |
| --- | --- | --- | --- |
| `pn` | `bmu_id`, `time_from`, `level_mw` | (`bmu_id`, `time_from`) | 46 800 |
| `b1610` | `bmu_id`, `time_from`, `quantity` | (`bmu_id`, `time_from`) | 45 120 |
| `weather` | `time_from`, `wind_speed` | `time_from` | 4 728 |
| `pn_total`, `b1610_total` | `time_from`, valeur, `units` (nombre d'unités sommées) | `time_from` | 9 360 / 9 024 |
| `remit` | tous les champs de l'avis + `raw_json` | `mrid` (+ index `bmu_id`, `event_status`) | 31 |

- On ne stocke que le **début** de chaque période. La fin (+30 min, +1 h pour le
  vent), le numéro de settlement period et `source` s'en déduisent, et l'API les
  recalcule (`app.utils.settlement_period`, propriétés des modèles). Avant, ces
  colonnes étaient stockées : elles étaient toujours cohérentes avec `time_from`
  (0 écart sur toute la base) et occupaient, avec leurs index, les trois quarts du fichier.
- `pn`, `b1610` et `weather` sont des tables `WITHOUT ROWID` : les lignes sont
  rangées dans l'index de la clé primaire, sans index en double.
- Les dates sont stockées en texte `YYYY-MM-DD HH:MM:SS.ffffff`, en UTC sans fuseau.
- `pn_total` et `b1610_total` contiennent la **somme des 4 unités Sofia**. Elle est
  recalculée dans la même transaction que chaque écriture dans `pn` ou `b1610`,
  pour les périodes touchées : les apps n'ont plus à additionner les unités.
- `init_db()` migre la base au démarrage : v1 → v2 dans une seule transaction
  (copie des tables, moyenne des segments PN par SP, `VACUUM`), puis v2 → v3
  (calcul des sommes). Le workflow de déploiement sauvegarde `sofia.db` avant
  chaque mise à jour.
- Toutes les écritures sont des **upserts** : on peut re-télécharger une plage
  sans créer de doublons. Les tâches utilisent `upsert_*_bulk` (un
  `INSERT … ON CONFLICT DO UPDATE` et un seul commit pour tout le lot).
- REMIT : une ligne par événement (`mrid`). Une révision n'écrase la ligne que si
  son `revision_number` est supérieur ou égal.
- Rien n'est supprimé : la base grossit d'environ 500 lignes par jour.

### API

| Endpoint | Réponse |
| --- | --- |
| `GET /pn/total?time_from&time_to` | somme des 4 unités Sofia (`bmu_id` `SOFIA_TOTAL`), périodes complètes seulement |
| `GET /pn/{bmu}?time_from&time_to` | points PN d'une BMU (`level_mw`) |
| `GET /pn/date-range`, `/pn/latest-settlement-period` | bornes des données |
| `GET /pn/top-production` | maxima de la somme des 4 unités (tout, 7, 30, 90 jours) |
| `POST /pn/refresh` | lance `fetch_next_pn` immédiatement |
| `GET /b1610/total?time_from&time_to` | somme des 4 unités Sofia, en MWh |
| `GET /b1610/{bmu}?time_from&time_to` | points B1610 d'une BMU (`quantity`, MWh) |
| `GET /b1610/latest-settlement-period`, `/b1610/top-production` | idem PN |
| `GET /weather?time_from&time_to`, `/weather/date-range`, `/weather/latest` | vent |
| `GET /remit`, `/remit/active/{bmu}`, `/remit/{id}`, `POST /remit/refresh` | avis REMIT |
| `GET /visual/pn`, `/visual/b1610`, `/visual/all` | pages HTML de contrôle (graphiques matplotlib, en MW et en UTC) |
| `GET /visual/quality`, `GET /quality` | qualité des données : couverture et retard de chaque série, complétude par jour, trous, anomalies (valeurs hors capacité, imports B1610, écarts PN/B1610, somme Sofia incomplète, avis REMIT incohérents) ; page HTML ou JSON |

Schéma complet : `/docs` ou `/openapi.json`. Les dates sont renvoyées **sans
fuseau** (`"2026-10-12T00:00:00"`) et les apps les lisent comme de l'UTC avec ce
format exact : ne pas le changer sans adapter les deux apps. Les réponses des
séries gardent tous leurs champs historiques (`time_to`, `settlement_period`,
`source`) même s'ils ne sont plus stockés : les versions déjà installées des
apps (Google Play) les lisent.

Les réponses de plus de 1 ko sont compressées en gzip (`GZipMiddleware`).
OkHttp (Android) et URLSession (iOS) décompressent sans configuration. Mesuré sur
l'historique complet : ×24 pour PN, ×18 pour B1610, ×12 pour le vent.

---

## 3. Apps Android et iOS

Les deux apps sont **identiques écran pour écran** : même API, mêmes calculs,
mêmes textes (anglais et français), mêmes trois widgets. iOS reprend l'architecture
Android : le package Swift `SofiaCore` correspond à la couche `data/` d'Android.

### Correspondance des fichiers

| Rôle | Android (`app/src/main/java/com/lemarc/sofia/`) | iOS |
| --- | --- | --- |
| Constantes (URL, BMU, 1 400 MW, fenêtres de temps) | `Constants.kt` | `SofiaCore/.../Constants.swift` |
| Client API + DTO | `data/api/SofiaApiService.kt` (Retrofit + Gson) | `SofiaApiService.swift`, `ApiModels.swift` (URLSession) |
| Modèles (`GraphPoint`, `TopWindows`, `RemitNotice`…) | `data/model/Model.kt` | `Models.swift` |
| Agrégation, records, utilitaires | dans les repositories | `Aggregation.swift`, `TimeUtils.swift` |
| Repositories (réseau → cache) | `data/repository/*.kt` | `Repositories.swift` |
| Cache local | `data/local/` (Room) | `LocalStore.swift` (fichiers JSON) |
| Réglages (mode test) | `data/settings/SettingsRepository.kt` (DataStore) | `SofiaSettings.swift`, `Sofia/Settings/` |
| Écran Graph | `ui/graph/` | `Sofia/Graph/` |
| Écran Alertes (REMIT) | `ui/remit/` | `Sofia/Remit/` |
| Écran Sofia | `ui/sofia/` | `Sofia/Home/` |
| Écran Réglages | `ui/settings/` | `Sofia/Settings/` |
| Composants (jauge, graphique, cartes, bandeaux) | `ui/components/` | `Sofia/Components/` |
| Widgets | `widget/modern/*GlanceWidget.kt` | `Widget/*Widget.swift` |
| Données et instantanés des widgets | `widget/modern/SofiaWidgetData.kt`, `SofiaWidgetSnapshots.kt` | `WidgetData.swift`, `WidgetSnapshots.swift` |
| Tests unitaires | `app/src/test/` (exemple vide) | `SofiaCore/Tests/` |

### Écrans

Barre de navigation en bas avec quatre onglets :

- **Graph** (onglet de départ)
  - jauge de production en direct : dernier PN cumulé comparé à 1 400 MW ;
  - puces de jeux de données : vent, production annoncée (PN), production réelle (B1610) ;
  - puces de fenêtre de temps : 6 h, 24 h, 48 h, 7 j, 1 mois, 3 mois, tout ;
  - graphique à deux axes : MW à gauche, vent en m/s à droite (converti depuis les
    km/h de l'API). Il se fait défiler et
    zoomer au pincement ; une puce de fenêtre zoome sur cette durée à la fin des données ;
  - records de production (7, 30, 90 jours, tout), horodatage de la dernière
    donnée et du dernier rafraîchissement ;
  - tirer pour rafraîchir, rafraîchissement automatique toutes les 60 s.
- **Alertes** (REMIT)
  - disponibilité par unité, calculée à partir des avis actifs ;
  - cartes d'avis, et un écran de détail : statut, capacités (normale,
    disponible, indisponible), chronologie (début, fin, publication), identifiants
    (BMU, participant, asset), cause, informations liées, profil d'indisponibilité ;
  - tirer pour rafraîchir, rafraîchissement automatique toutes les 120 s.
- **Sofia** : présentation du parc et carte.
- **Réglages** : mode test (`T_HEYM11` à la place des quatre unités Sofia) et avertissement.

Erreurs réseau : bandeaux non bloquants, et les dernières données en cache
restent affichées. États vides : carte explicative quand une fenêtre ne
contient aucune donnée.

### Flux de données

À chaque rafraîchissement de l'écran Graph :

1. **Téléchargement.** En parallèle, sur **tout l'historique** (du 1er avril 2026
   à maintenant) : `GET /pn/total`, `GET /b1610/total` et `GET /weather`. La somme
   des 4 unités Sofia est calculée par le serveur. En mode test, les apps lisent
   directement `GET /pn/T_HEYM11` et `GET /b1610/T_HEYM11`. L'écran Alertes appelle
   `GET /remit/active/{bmu}` pour chaque BMU et dédoublonne par `id`.
2. **Mise en forme.** La série reçue devient des `GraphPoint` (id `SOFIA_TOTAL`,
   ou `T_HEYM11`). Les fonctions `aggregateProduction` / `aggregateB1610` sont
   gardées mais n'additionnent plus rien (une seule série). Le B1610 (MWh par
   demi-heure) est multiplié par 2 à l'affichage pour être comparable au PN (MW).
   Les records (7, 30, 90 jours, tout) sont calculés localement.
   Les versions précédentes des apps (déjà installées) téléchargeaient les 4 unités
   et les additionnaient : les endpoints par unité restent disponibles.
3. **Cache hors ligne.** La série agrégée remplace entièrement le cache :
   - Android : base Room `sofia_database` (tables `production_points`,
     `b1610_points`, `weather_points`, `remit_notices` ; sauf pour le vent, chaque
     ligne est marquée `isTestMode`). Chaque rafraîchissement fait « tout supprimer
     puis tout insérer » dans une transaction. L'interface observe ces tables (Flow) :
     au lancement ou hors ligne, elle affiche immédiatement la dernière copie.
   - iOS : `SofiaLocalStore` (actor) écrit un fichier JSON par « table » (suffixé
     selon le mode test) dans le conteneur de l'App Group `group.com.lemarc.Sofia`,
     ou dans Application Support sans App Group, et garde une copie en mémoire.
     Même logique de remplacement complet.
4. **Affichage.** Le filtrage par fenêtre de temps se fait en mémoire. iOS
   regroupe les points (≈ 200 groupes) au-delà de 400 points pour garder un
   graphique fluide.

Il n'y a pas de synchronisation incrémentale : chaque rafraîchissement
re-télécharge et réécrit tout l'historique. En mode normal, cela fait environ
1,3 Mo (PN) + 1,2 Mo (B1610) + 0,5 Mo (vent) ≈ 3 Mo de JSON, soit **~160 ko
transférés** avec le gzip du serveur. Avant (4 unités, sans gzip) : ~10 Mo.

### Widgets

| Widget | Tailles | Contenu |
| --- | --- | --- |
| Production | petit, moyen | jauge, PN actuel, production réelle, heure de mise à jour |
| Disponibilité par unité | petit, moyen | disponibilité REMIT de chaque unité |
| Graph | moyen, grand | production annoncée (et réelle à partir de 7 jours) sur une fenêtre configurable |

- Rafraîchis toutes les ~30 min : WorkManager (`SofiaWidgetRefreshWorker`) sur
  Android, timeline WidgetKit `.after(30 min)` sur iOS.
- Ils téléchargent **seulement leur fenêtre** (24 h par défaut), avec les mêmes
  endpoints de somme que l'app, et n'écrivent pas dans le cache de l'app.
- En cas d'échec réseau : cache de l'app, puis dernier instantané réussi du
  widget (fichier JSON `widget_<nom>.json`).
- iOS : sans App Group, le widget ne voit ni le mode test ni le cache de l'app.
