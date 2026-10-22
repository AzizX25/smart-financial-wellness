# Démonstration superviseur synthétique

Ce scénario prépare un jeu de données local reproductible pour présenter Smart Financial Wellness Advisor. Tous les noms, comptes, références, transactions et montants sont fictifs. Ils ne doivent jamais être interprétés comme des données bancaires réelles.

Le scénario versionné `supervisor-demo-v1` utilise l'identité plateforme `amine.bensalem@example.test` et l'identifiant mock-bank `amine.bensalem.demo`. Aucun mot de passe n'est fourni ni enregistré dans Git.

## Prérequis

- Docker Desktop et Docker Compose v2 ;
- Windows PowerShell 5.1 ou PowerShell 7 ;
- la configuration locale habituelle dans `.env` ;
- Mailpit disponible avec le reste de la pile pour la vérification normale de l'adresse électronique ;
- un administrateur mock-bank local existant.

Copier le modèle privé puis renseigner les trois valeurs secrètes sans les partager :

```powershell
Copy-Item .env.demo.example .env.demo
# Éditer .env.demo localement :
# DEMO_PLATFORM_PASSWORD=...
# DEMO_BANK_PASSWORD=...
# DEMO_MOCK_BANK_ADMIN_PASSWORD=...
```

`.env.demo` est ignoré par Git. Les scripts refusent de démarrer si un mot de passe requis manque et n'affichent ni mot de passe, ni jeton, ni en-tête d'autorisation.

## Démarrage, création et vérification

Le point de mutation de fixtures mock-bank est désactivé par défaut. Le second fichier active ce point uniquement pour cette exécution locale :

```powershell
docker compose --env-file .env --env-file .env.demo up -d --build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/seed_supervisor_demo.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/verify_supervisor_demo.ps1
```

Une date de référence du mois civil courant peut être injectée. Sans paramètre, la date locale courante est utilisée. Cette limite respecte le caractère non modifiable des allocations Goals passées :

```powershell
$asOf = (Get-Date).ToString('yyyy-MM-dd')
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/seed_supervisor_demo.ps1 -AsOfDate $asOf
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/verify_supervisor_demo.ps1 -AsOfDate $asOf
```

À version et mois de référence identiques, les références externes et le contenu de chaque opération déjà arrivée restent identiques. Une date ultérieure du même mois ajoute seulement les opérations planifiées devenues échues. Une nouvelle exécution vérifie l'identité existante, complète les éléments manquants et ne duplique ni utilisateur, compte, transaction, contribution, objectif, allocation ou connexion.

Lors d'un changement de mois, la fixture bancaire conserve l'ancrage de ses deux
écritures d'ouverture originales. Les montants et l'achat inhabituel ne sont pas
recalculés dans une fenêtre glissante : seuls les éléments manquants sont ajoutés.
L'historique grandit au-delà de huit mois ; les soldes évoluent selon le registre,
sans nouveau solde d'ouverture ni ajustement artificiel. Le vérificateur utilise
cet ancrage et rapproche les soldes comptabilisé/disponible avec les écritures.
Les anciennes opérations en attente sont conservées, sans inventer leur règlement.
Une date antérieure à des opérations déjà présentes est refusée, jamais utilisée
pour les supprimer. Une fixture altérée ou sans ancrage vérifiable reste refusée.

## Contenu du scénario

- au moins huit mois terminés et le mois courant, sans opération comptabilisée dans le futur ;
- un compte courant TND et un compte épargne TND aux identifiants explicitement synthétiques ;
- salaires, logement, factures, alimentation, transport, santé, restaurants, achats, abonnements et virements d'épargne réalistes mais fictifs ;
- un achat élevé, une opération en attente et des virements internes appariés ;
- un mélange de catégories déterministes/manuelles, de cas à revoir et d'exclusions ;
- une mémoire marchande privée créée par une attribution manuelle antérieure, puis réutilisée comme suggestion `USER_MEMORY` en attente ;
- un Salary Plan TND courant et futur à capacité d'épargne positive ;
- deux objectifs avec progression manuelle et allocations respectant cette capacité ;
- les historiques et instantanés nécessaires à la prévision courante, avec le repli déterministe existant si le service IA Forecast est indisponible.

Le cœur du scénario ne nécessite ni Ollama ni recherche web. Pour une présentation distincte de l'enrichissement marchand, démarrer Ollama/configurer sa clé privée selon le guide existant, puis définir explicitement `DEMO_RUN_MERCHANT_AI=true` dans `.env.demo` avant de relancer le script.

## Parcours de démonstration conseillé

1. Se connecter à la plateforme avec `amine.bensalem@example.test`.
2. Présenter le Dashboard.
3. Montrer la connexion `amine.bensalem.demo` et la synchronisation manuelle.
4. Parcourir les transactions, les catégories, les revues et la mémoire privée.
5. Ouvrir Salary Planning.
6. Ouvrir Goals, les allocations et les simulations temporaires.
7. Ouvrir Forecast et expliquer le repli déterministe.

### Score, pistes et lecture de ticket

Sur le Dashboard, présenter le score et ses facteurs disponibles, puis **Mes
prochaines étapes** et leurs justifications. Accepter ou masquer une piste ne
modifie aucun budget, objectif ni transaction. Les deux facteurs non calculables
restent explicitement indisponibles.

Les [explications et tickets locaux](../api/local-assistance.md) sont optionnels,
désactivés par défaut et expérimentaux. Après vérification d’Ollama, montrer une
explication française, puis un ticket entièrement synthétique sur Transactions :
lecture, correction humaine, export CSV et prévisualisation sans confirmer un
doublon bancaire. Aucune transaction n’est créée par la lecture du ticket.

Rejouer aussi le parcours avec ces options désactivées : les pistes déterministes,
l’import CSV et les fonctionnalités financières doivent rester utilisables.

Les mots de passe restent ceux choisis dans `.env.demo` et ne doivent pas apparaître dans une capture, un journal ou une commande partagée.

## Réinitialisation sûre

Par défaut, aucune suppression n'est réalisée. Pour retirer uniquement la fixture mock-bank explicitement marquée :

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/seed_supervisor_demo.ps1 -ResetDemoOnly
```

Cette opération exige la version, l'identité fixe et une confirmation interne exacte ; elle refuse les comptes ou transactions non marqués. Elle ne supprime pas l'utilisateur ni l'historique local déjà synchronisé dans l'application. Le backend ne fournit pas encore de suppression complète de cet agrégat par API : pour une remise à zéro intégrale, utiliser uniquement un environnement de démonstration jetable et isolé, ou le futur workflow métier de suppression. Ne jamais supprimer un volume partagé ou modifier un utilisateur ordinaire.
