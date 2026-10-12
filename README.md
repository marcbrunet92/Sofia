# Sofia

Suivi du parc éolien offshore **Sofia** (mer du Nord, 1 400 MW) : production
annoncée (PN), production réelle mesurée (B1610), vent, et indisponibilités
publiées (REMIT).

Ce dépôt est un **superprojet git** : il ne contient pas de code, seulement trois
sous-modules et la documentation commune.

| Dossier | Dépôt | Branche | Contenu |
| --- | --- | --- | --- |
| `Sofia_Server/` | [Sofia_Server](https://github.com/marcbrunet92/Sofia_Server) | **`master`** | API FastAPI + SQLite, collecte Elexon / Open-Meteo, déployée sur le Raspberry Pi (`sofia.lemarc.fr`) |
| `Sofia_Android/` | [Sofia_Android](https://github.com/marcbrunet92/Sofia_Android) | `main` | App Kotlin / Jetpack Compose + widgets Glance |
| `Sofia_iOS/` | [Sofia_iOS](https://github.com/marcbrunet92/Sofia_iOS) | `main` | App SwiftUI + widgets WidgetKit (package partagé `SofiaCore`) |

Documentation :

- [ARCHITECTURE.md](ARCHITECTURE.md) : fonctionnalités, correspondance Android ↔ iOS,
  collecte et stockage côté serveur, gestion des données dans les apps (cache hors
  ligne, agrégation, widgets).
- [ROADMAP.md](ROADMAP.md) : problèmes connus (dont la fiabilité des tests),
  optimisations du serveur et des apps, idées d'évolution.

> **Tests** : les tests du projet (serveur, Android, iOS) ne sont pas fiables pour
> l'instant, et un résultat vert ne prouve pas que ça marche. On traitera ce
> problème plus tard ; voir [ROADMAP.md](ROADMAP.md#tests--à-reprendre).

---

## La règle : un commit Sofia = Android et iOS au même stade

Chaque commit de ce dépôt fige un trio de versions (serveur, Android, iOS). Un
commit qui avance `Sofia_Android` doit aussi avancer `Sofia_iOS` vers une version
équivalente (mêmes écrans, mêmes données, même comportement), et inversement.
Le serveur peut avancer seul.

Le hook [`.githooks/commit-msg`](.githooks/commit-msg) vérifie cette règle :

- les deux pointeurs d'app changent : le commit passe ;
- un seul change : le commit est **refusé**, sauf si le message contient
  `[android-only]` ou `[ios-only]` (correctif propre à une plateforme, sans
  écart fonctionnel, par exemple un crash Android) ;
- `git commit --no-verify` permet de forcer exceptionnellement.

Le hook est actif une fois `core.hooksPath` configuré (voir ci-dessous).

---

## Git submodules : mode d'emploi

### Le principe

Pour chaque sous-module, le superprojet enregistre **un pointeur vers un commit
précis**, pas les fichiers. Conséquences :

- Un sous-module est un dépôt git complet. On y fait `commit`, `push`, `pull`
  comme d'habitude, et ces commits vivent sur GitHub dans son propre dépôt.
- Commiter dans `Sofia_iOS/` ne change rien au superprojet tant qu'on n'a pas
  **aussi** commité le nouveau pointeur dans `Sofia/` (`git add Sofia_iOS`).
- Un `git submodule update` place le sous-module sur le commit pointé, en
  **detached HEAD** (pas sur une branche). Avant de coder, on repasse sur la branche.

### Configuration (une fois par clone)

```bash
git config core.hooksPath .githooks
```

```bash
git config push.recurseSubmodules check
```

```bash
git config submodule.recurse true
```

- `core.hooksPath` active le hook de la règle Android/iOS.
- `push.recurseSubmodules check` refuse de pousser le superprojet si un pointeur
  désigne un commit de sous-module **pas encore poussé** (sinon personne d'autre
  ne pourrait le récupérer). Avec `on-demand` à la place de `check`, git pousse
  ces sous-modules automatiquement.
- `submodule.recurse true` fait que `git pull`, `git checkout` et `git switch`
  mettent aussi les sous-modules à jour.

Optionnel : `git config diff.submodule log` pour que `git diff` liste les commits
inclus dans un changement de pointeur au lieu d'afficher deux SHA.

Sous Windows, si `Sofia_Server/` ou les autres dossiers apparaissent modifiés sans
raison (fins de ligne), vérifier `git config core.autocrlf` dans le sous-module.

### Cloner

```bash
git clone --recurse-submodules git@github.com:marcbrunet92/Sofia.git
```

Si le dépôt a été cloné sans `--recurse-submodules` (dossiers vides) :

```bash
git submodule update --init
```

### Récupérer le travail existant

Mettre le superprojet **et** les sous-modules sur les versions enregistrées :

```bash
git pull
```

(Sans `submodule.recurse`, il faut ensuite `git submodule update --init`.)

Amener les sous-modules sur le dernier commit de leur branche (`main` ou
`master`, voir `.gitmodules`), même si le superprojet ne pointe pas encore dessus :

```bash
git submodule update --remote
```

Les pointeurs apparaissent alors modifiés dans `git status` ; on les commite
comme décrit plus bas, en respectant la règle Android/iOS.

### Travailler sur une fonctionnalité (Android + iOS)

1. Se placer sur la branche de chaque sous-module (sortir du detached HEAD) :

   ```bash
   git -C Sofia_Android switch main
   ```

   ```bash
   git -C Sofia_iOS switch main
   ```

2. Coder, puis commiter **dans chaque sous-module** :

   ```bash
   git -C Sofia_Android commit -am "Graph: ajoute la fenêtre 1 an"
   ```

   ```bash
   git -C Sofia_iOS commit -am "Graph: ajoute la fenêtre 1 an"
   ```

3. Pousser les sous-modules **en premier** :

   ```bash
   git -C Sofia_Android push
   ```

   ```bash
   git -C Sofia_iOS push
   ```

4. Enregistrer les deux nouveaux pointeurs dans le superprojet, puis pousser :

   ```bash
   git add Sofia_Android Sofia_iOS
   ```

   ```bash
   git commit -m "Graph: fenêtre 1 an (Android + iOS)"
   ```

   ```bash
   git push
   ```

Pour le serveur, c'est la même chose avec `Sofia_Server` (branche `master`).
Le déploiement sur le Raspberry Pi se lance à la main depuis l'onglet *Actions*
du dépôt Sofia_Server (workflow *Deploy to Raspberry Pi*) : il fait
`git reset --hard origin/master` sur le Pi, donc seul ce qui est **poussé** sur
`master` est déployé. Il sauvegarde d'abord `sofia.db` (dossier `backups/` sur le
Pi, 5 dernières copies) puis vérifie que l'API répond après le redémarrage.
Quand une version des apps utilise un nouvel endpoint, déployer le serveur
**avant** de publier les apps.

### Lire `git status` dans le superprojet

| Affichage | Signification | Action |
| --- | --- | --- |
| `modified: Sofia_iOS (new commits)` | le sous-module est sur un autre commit que celui enregistré | `git add Sofia_iOS` + commit (ou `git submodule update` pour revenir en arrière) |
| `modified: Sofia_iOS (modified content)` | modifications non commitées **dans** le sous-module | aller commiter dans le sous-module |
| `modified: Sofia_iOS (untracked content)` | fichiers non suivis dans le sous-module | les commiter ou les ignorer dans le sous-module |

`git submodule status` affiche le commit de chaque sous-module : `+` devant = 
différent du pointeur enregistré, `-` = pas initialisé.

### Pièges fréquents

- **Commit fait en detached HEAD** dans un sous-module : il n'est sur aucune
  branche. Le rattraper :

  ```bash
  git -C Sofia_iOS branch rescue
  ```

  ```bash
  git -C Sofia_iOS switch main
  ```

  ```bash
  git -C Sofia_iOS merge rescue
  ```

- **Pointeur poussé vers un commit non poussé** : un clone échoue avec
  `not our ref` / `did not contain`. Pousser le sous-module concerné
  (`push.recurseSubmodules check` évite ce cas).
- **`git submodule update` après avoir codé** : remet le sous-module sur l'ancien
  pointeur. Les commits ne sont pas perdus (ils restent sur la branche) mais il
  faut refaire `git switch main`.
- **Le serveur est sur `master`**, les apps sur `main`.
- `Sofia_Server/sofia.db` (base de prod téléchargée ou base locale) est ignorée
  par git : elle n'est jamais commitée.

### Aide-mémoire

| Je veux… | Commande |
| --- | --- |
| cloner tout | `git clone --recurse-submodules <url>` |
| initialiser après un clone simple | `git submodule update --init` |
| tout mettre à jour | `git pull` (avec `submodule.recurse true`) |
| avancer les sous-modules sur leur branche | `git submodule update --remote` |
| voir l'état des pointeurs | `git submodule status` |
| voir les commits derrière un pointeur | `git diff --submodule=log` |
| lancer une commande dans chaque sous-module | `git submodule foreach 'git status -s'` |
| enregistrer les nouveaux pointeurs | `git add Sofia_Android Sofia_iOS && git commit` |

---

## Démarrer chaque projet

### Serveur

```bash
cd Sofia_Server && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
```

```bash
cd Sofia_Server && .venv/Scripts/uvicorn agsi:app --reload
```

(Sous macOS / Linux : `.venv/bin/...`.) Au démarrage, le serveur crée ou complète
`sofia.db` depuis Elexon et Open-Meteo, puis lance les tâches périodiques.
Documentation interactive : <http://localhost:8000/docs>. Qualité des données
(trous, retards, anomalies) : <http://localhost:8000/visual/quality>, ou
<https://sofia.lemarc.fr/visual/quality> en production.

### Android

Ouvrir `Sofia_Android/` dans Android Studio. L'app appelle l'API de production
(`BASE_URL` dans `Constants.kt`).

### iOS

Ouvrir `Sofia_iOS/Sofia.xcodeproj` avec Xcode 26 ou plus (cible iOS 17). Pour que
le widget partage le mode test et le cache de l'app, ajouter l'App Group
`group.com.lemarc.Sofia` aux cibles `Sofia` et `WidgetExtension`.
