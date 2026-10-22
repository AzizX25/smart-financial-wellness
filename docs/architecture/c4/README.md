# Modèle C4 — Smart Financial Wellness Advisor

Ces vues décrivent le **code de la version actuelle du dépôt**.
Elles ne décrivent pas uniquement une architecture cible.
Les exemples bancaires restent synthétiques ; les fonctions IA optionnelles
dépendent des flags et des modèles installés. La génération des diagrammes
ne vérifie pas la disponibilité des services ni la validation globale d'une release.

## Voir les diagrammes

La version native **Structurizr** utilise [workspace.dsl](workspace.dsl).
Le conteneur `sfwa-structurizr` sert les diagrammes sur
**[http://localhost:8085](http://localhost:8085)** : cliquer sur **Diagrams**,
puis choisir une vue. Les dix vues couvrent C1, C2 application, C2 mock-bank,
les six vues C3 et le déploiement local. Les mises en page sont automatiques.

Le viewer est lié uniquement à `127.0.0.1`. Il monte ce dossier, pas le dépôt
entier, et ne contacte aucune API financière. Il ne fait pas partie du
déploiement applicatif Compose et ne modifie ni les services ni les volumes
de l'application.

Pour démarrer le viewer depuis la racine du dépôt si le conteneur n'existe pas :

```powershell
$c4Directory = (Resolve-Path .\docs\architecture\c4).Path
docker run -d --name sfwa-structurizr `
  -p 127.0.0.1:8085:8080 `
  --mount "type=bind,source=$c4Directory,target=/usr/local/structurizr" `
  structurizr/structurizr:latest local
```

S'il existe déjà : `docker start sfwa-structurizr`.
Pour l'arrêter sans supprimer les fichiers : `docker stop sfwa-structurizr`.
L'image installée lors de la validation est **Structurizr v2026.05.22**.
Le mode `local` et le montage de workspace suivent le
[guide officiel Structurizr](https://docs.structurizr.com/local/quickstart).
Le conteneur lancé initialement sans commande, port ni montage est conservé ;
il ne peut pas charger ce workspace avec sa configuration initiale.

### Ancien export hors ligne

L'export précédent reste disponible : ouvrir **[index.html](index.html)** dans Brave, Chrome ou Edge.
Depuis la racine du dépôt, PowerShell peut ouvrir le fichier ainsi :

```powershell
Invoke-Item .\docs\architecture\c4\index.html
```

Si Windows ouvre un éditeur, clic droit sur `index.html` → **Ouvrir avec** →
votre navigateur. Aucun serveur, Docker, extension, compte ou accès Internet
n'est nécessaire.

Dans le viewer :

1. Choisir une vue dans **Diagramme**.
2. Utiliser **Zoom +**, **Zoom −** et **Ajuster** ; les grandes vues défilent.
3. Lire les relations numérotées sous le schéma et les notes de portée.
4. Déplier **Évidence dans le code** pour retrouver les fichiers inspectés.
5. Utiliser **Télécharger le SVG** pour le rapport ou une présentation.
6. Utiliser **Imprimer / PDF** pour exporter la vue sélectionnée depuis le navigateur.

Le viewer et les SVG sont autonomes : aucun CDN et aucun appel aux API du projet.
Pour partager le viewer, copier le dossier entier ; un SVG seul peut aussi être
ouvert, agrandi ou incorporé dans un rapport indépendamment.

## Les neuf vues de l'export hors ligne

| Vue | Portée | Fichier |
| --- | --- | --- |
| C1 — Contexte | Utilisateur, développeur, administrateur et systèmes externes | [01-context.svg](01-context.svg) |
| C2 — Conteneurs | Frontend, backend, PostgreSQL, FastAPI, pipeline, artefacts et mock-bank séparé | [02-containers.svg](02-containers.svg) |
| C3 — Backend données | Sécurité, identité, profil, banque, import CSV, catégorisation, lectures et audit | [03-backend-data.svg](03-backend-data.svg) |
| C3 — Backend insights | Salary Planning, Goals, Forecast, score, recommandations et assistance | [04-backend-insights.svg](04-backend-insights.svg) |
| C3 — Frontend | Routes, session, données partagées, pages, widgets et client HTTP | [05-frontend.svg](05-frontend.svg) |
| C3 — AI service | Runtimes, contrats stricts, enrichissement, admission locale et vision | [06-ai-service.svg](06-ai-service.svg) |
| C3 — Mock-bank | Authentification, ownership, lectures, administration et fixture démo | [07-mock-bank.svg](07-mock-bank.svg) |
| C3 — Pipeline ML | Préparation, splits temporels, expériences et export contrôlé hors ligne | [08-ml-pipeline.svg](08-ml-pipeline.svg) |
| Déploiement local | Navigateurs, Nginx, services Compose, volumes et Ollama sur l'hôte | [09-deployment.svg](09-deployment.svg) |

Les vues C3 représentent les composants logiques des applications existantes :
un bloc peut regrouper le contrôleur, les services et les repositories d'une
fonctionnalité. Ces blocs ne sont pas des microservices supplémentaires.
La vue du pipeline précise un flux hors ligne, pas un service réseau.

Un conteneur C4 est une application ou un stockage ; il ne signifie pas
nécessairement « conteneur Docker ». Les instances et ports locaux ont donc
une vue de déploiement distincte. Le niveau **Code / C4 niveau 4** n'est pas
recopié pour chaque classe : ces vues couvrent les frontières utiles de l'application,
conformément au [choix des niveaux C4](https://c4model.com/diagrams) et à la
[définition d'un diagramme de conteneurs](https://c4model.com/diagrams/container).

## Décisions importantes représentées

- La SPA principale appelle uniquement le backend principal. La console
  mock-bank-ui est un client d'administration indépendant.
- Le backend reste un monolithe modulaire Java 21 / Spring Boot 4.1,
  avec propriétaires issus du principal et statuts relus en PostgreSQL.
- Chaque Spring Boot possède sa base et son historique Flyway. Le backend
  applicatif comporte V1–V22 dans le worktree ; le mock-bank comporte V1–V4.
  Hibernate valide le schéma ; Flyway en est le mécanisme de gestion.
- Le flux bancaire courant utilise un login synthétique et un JWT fournisseur.
  Des entités SQL/JPA de conception OAuth existent, mais les endpoints
  Authorization Code / PKCE ne sont pas implémentés ; ils ne figurent pas comme flux actif.
- L'import CSV reste indépendant de la synchronisation. Les corrections manuelles
  utilisent la catégorie actuelle de la transaction pour les dépenses réelles.
- La reconnaissance marchande crée une suggestion **PENDING** ; une revue humaine
  est requise. Règles déterministes et mémoire privée précèdent le recours IA.
- Salary Planning produit la capacité d'épargne ; Goals lit cette capacité
  persistée et gère ses contributions / allocations. Une allocation ne crée
  aucun versement. Les simulations Goals et Forecast restent temporaires.
- Forecast conserve son calcul déterministe et peut ajouter le résidu Huber
  synthétique TND. Les autres devises conservent le déterministe ; aucune conversion.
- Le score et les règles de recommandation sont déterministes. Les recommandations
  s'appuient sur les facteurs V21 immuables ; V22 permet ces justifications.
- Ollama local peut expliquer une règle contrôlée ou lire un ticket éphémère.
  La recherche web externe est réservée à l'enrichissement marchand et est optionnelle.
- Le ticket est revu puis exporté en CSV. L'extraction ne crée pas de transaction.
- Les emails d'actions de compte sont envoyés après commit, via Mailpit en développement.
- Le pipeline prépare / entraîne / évalue hors ligne. Les modèles et métadonnées
  sont inclus dans l'image ai-service lors du build ; aucun entraînement à la requête.

Les écrans d'export/suppression de données et certains consentements du profil
restent des éléments de prototype ; ils ne sont pas représentés comme workflows
métier implémentés. L'état des flags et des modèles Ollama n'est pas déduit du
fichier `.env` privé et n'est pas présumé par les diagrammes.

## Source canonique et régénération

**[workspace.dsl](workspace.dsl)** est désormais la source canonique native.
Modifier ce fichier puis recharger Structurizr pour actualiser ses diagrammes.
Les relations optionnelles sont en pointillés ; les modèles représentent des
composants logiques, pas une entité par classe. Les ports hôte sont configurables.
`workspace.json` contient le modèle dérivé et les mises en page sauvegardées par
Structurizr ; ce n'est pas une seconde source d'architecture. Le répertoire
`.structurizr/` (logs, index et caches locaux) est ignoré par Git.

Validation native, sans démarrer les services applicatifs :

```powershell
$c4Directory = (Resolve-Path .\docs\architecture\c4).Path
docker run --rm `
  --mount "type=bind,source=$c4Directory,target=/usr/local/structurizr,readonly" `
  structurizr/structurizr:latest validate -w /usr/local/structurizr/workspace.dsl
```

**[model.json](model.json)**, **[render.py](render.py)**, le viewer HTML et les SVG
sont conservés comme export historique autonome. Ils ne sont pas régénérés
automatiquement depuis le DSL et peuvent diverger après de futures éditions.
Pour vérifier uniquement cet ancien export :

```powershell
python .\docs\architecture\c4\render.py
python .\docs\architecture\c4\render.py --check
```

Le check vérifie les identifiants et relations, les chemins référencés, les
frontières des composants, les boîtes sans chevauchement, les chemins de flèches,
les débordements de texte, la syntaxe XML des SVG et l'égalité des fichiers générés
avec le modèle. Il ne remplace pas une revue visuelle dans le navigateur.

Ne pas éditer manuellement les SVG ou `index.html`. Pour une évolution de
l'architecture, commencer par `workspace.dsl` ; ne pas considérer un ancien
export comme une preuve que le service ou la fonctionnalité fonctionne.
