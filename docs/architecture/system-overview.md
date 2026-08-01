# Vue d'architecture démontrable

La plateforme principale et le fournisseur bancaire simulé restent deux
systèmes séparés. Le frontend ne contacte que le backend principal ; il ne lit
jamais directement une base de données et n'appelle jamais le mock-bank.

```mermaid
flowchart LR
    frontend["Frontend React<br/>Vite + JavaScript"]
    backend["Backend principal<br/>Spring Boot 4.1 / Java 21"]
    postgres[("PostgreSQL principal<br/>schéma smart_financial_wellness")]
    mockbank["Mock-bank séparé<br/>Spring Boot / Java 21"]
    mockdb[("PostgreSQL mock-bank<br/>historique Flyway séparé")]

    frontend -->|"API REST uniquement"| backend
    backend -->|"JPA / Flyway"| postgres
    backend -->|"API bancaire en lecture seule"| mockbank
    mockbank -->|"données bancaires fictives"| mockdb

    classDef ui fill:#e7f3ef,stroke:#1f6b63,color:#173c3a
    classDef service fill:#fff4de,stroke:#c88a32,color:#44331d
    classDef database fill:#eef0fa,stroke:#5967a5,color:#252d59
    class frontend ui
    class backend,mockbank service
    class postgres,mockdb database
```

## Frontières de responsabilité

- Le frontend présente l'expérience utilisateur et remplacera progressivement
  ses fixtures par des appels au backend principal.
- Le backend porte les règles de bien-être financier et orchestre les accès à
  PostgreSQL, au mock-bank et, plus tard, au service d'inférence.
- Le mock-bank simule uniquement un fournisseur bancaire externe en lecture
  seule. Il ne contient aucune règle métier de bien-être financier.
- Les deux applications Spring Boot ont leurs propres données, leurs propres
  migrations Flyway et aucune clé étrangère entre leurs bases.

La page « Banque Démo » du prototype est une maquette visuelle du futur écran
de consentement. Elle ne réalise actuellement aucun appel au mock-bank.

## État actuel du mock-bank

Le dossier `mock-bank/` contient une application Java 21 / Spring Boot 4.1
indépendante. Elle utilise sa propre base PostgreSQL, le schéma `mock_bank` et
un historique Flyway distinct de celui du backend principal. Toutes ses données
sont synthétiques.

La fondation actuelle fournit les mappings JPA, repositories et services
internes de lecture pour les clients, comptes, soldes et transactions. Elle
expose `GET /actuator/health` ainsi que trois routes de comptes en lecture seule :

- `GET /api/v1/customers/{customerId}/accounts` retourne les métadonnées
  publiques dans un ordre déterministe ;
- `GET /api/v1/customers/{customerId}/accounts/{accountId}` retourne les
  métadonnées publiques d'un compte appartenant au client ;
- `GET /api/v1/customers/{customerId}/accounts/{accountId}/balance` retourne
  uniquement le contrat de solde.

Les entités JPA, UUID internes et soldes ne sont pas exposés par les routes de
métadonnées. Un client inconnu, un compte inconnu ou un compte appartenant à
un autre client produit un JSON `404`; le dernier cas utilise toujours
`Account not found` afin d'empêcher l'énumération des comptes.

Les API de transactions, consentement, codes d'autorisation et jetons ne sont
pas encore implémentées. Une autorisation simulée, légère et strictement en
lecture seule sera ajoutée dans un incrément ultérieur.
