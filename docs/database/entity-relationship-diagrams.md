# Diagrammes de la base PostgreSQL

Ces vues Mermaid sont dérivées des migrations Flyway V1 à V10 présentes dans
`backend/src/main/resources/db/migration`. Elles privilégient les clés primaires,
les principales clés étrangères et les cardinalités utiles à une présentation.
Les colonnes techniques et les contraintes détaillées restent documentées dans
[le schéma Flyway](flyway-schema.md).

## A. Identité et sécurité

Cette vue regroupe l'identité métier, les préférences, l'authentification locale,
les consentements et les workflows liés aux données personnelles. Les mots de
passe et jetons sont représentés uniquement par leurs empreintes. Le journal
`audit_log_entry` conserve volontairement des identifiants sans clé étrangère
vers l'utilisateur afin de rester exploitable après un workflow de suppression.

```mermaid
erDiagram
    app_user {
        UUID id PK
        VARCHAR status
    }
    interface_preference {
        UUID user_id PK, FK
    }
    financial_preference {
        UUID user_id PK, FK
    }
    notification_preference {
        UUID id PK
        UUID user_id FK
    }
    authentication_identity {
        UUID id PK
        UUID user_id FK, UK
        VARCHAR normalized_email UK
    }
    password_credential {
        UUID authentication_identity_id PK, FK
        VARCHAR password_hash
    }
    refresh_session {
        UUID id PK
        UUID authentication_identity_id FK
        UUID replaced_by_session_id FK
        CHAR token_hash UK
    }
    account_action_token {
        UUID id PK
        UUID authentication_identity_id FK
        CHAR token_hash UK
    }
    user_consent {
        UUID id PK
        UUID user_id FK
    }
    consent_event {
        UUID id PK
        UUID user_consent_id FK
    }
    audit_log_entry {
        UUID id PK
        UUID actor_id
        UUID resource_id
    }
    personal_data_request {
        UUID id PK
        UUID user_id FK
    }
    personal_data_export {
        UUID id PK
        UUID personal_data_request_id FK, UK
    }

    app_user ||--o| interface_preference : "configure"
    app_user ||--o| financial_preference : "configure"
    app_user ||--o{ notification_preference : "configure"
    app_user ||--o| authentication_identity : "s'authentifie avec"
    authentication_identity ||--o| password_credential : "possède"
    authentication_identity ||--o{ refresh_session : "ouvre"
    refresh_session o|--o| refresh_session : "est remplacée par"
    authentication_identity ||--o{ account_action_token : "reçoit"
    app_user ||--o{ user_consent : "accorde"
    user_consent ||--o{ consent_event : "historise"
    app_user ||--o{ personal_data_request : "demande"
    personal_data_request ||--o| personal_data_export : "produit"
```

## B. Données financières

La donnée canonique est `transaction_record`. Une transaction est manuelle,
issue d'un fichier ou issue d'une synchronisation bancaire, jamais plusieurs à
la fois. `source_file` identifie le fichier par SHA-256, tandis que les
autorisations bancaires ne conservent qu'une référence vers le gestionnaire de
secrets.

```mermaid
erDiagram
    app_user {
        UUID id PK
    }
    bank_connection {
        UUID id PK
        UUID user_id FK
    }
    bank_connection_authorization {
        UUID id PK
        UUID bank_connection_id FK
        UUID superseded_by_authorization_id FK
    }
    financial_account {
        UUID id PK
        UUID user_id FK
        UUID bank_connection_id FK
    }
    sync_job {
        UUID id PK
        UUID bank_connection_id FK
        UUID authorization_id FK
    }
    balance_snapshot {
        UUID id PK
        UUID financial_account_id FK
        UUID sync_job_id FK
    }
    source_file {
        UUID id PK
        UUID user_id FK
        CHAR sha256
    }
    import_job {
        UUID id PK
        UUID user_id FK
        UUID financial_account_id FK
        UUID source_file_id FK
    }
    import_mapping {
        UUID id PK
        UUID import_job_id FK, UK
    }
    column_mapping {
        UUID id PK
        UUID import_mapping_id FK
    }
    rejected_import_row {
        UUID id PK
        UUID import_job_id FK
    }
    category {
        UUID id PK
        UUID user_id FK
        UUID parent_id FK
    }
    transaction_record {
        UUID id PK
        UUID user_id FK
        UUID financial_account_id FK
        UUID category_id FK
        UUID import_job_id FK
        UUID source_file_id FK
        UUID sync_job_id FK
        UUID applied_category_prediction_id FK
    }
    category_prediction {
        UUID id PK
        UUID transaction_id FK
        UUID category_id FK
    }
    internal_transfer {
        UUID id PK
        UUID outgoing_transaction_id FK, UK
        UUID incoming_transaction_id FK, UK
    }

    app_user ||--o{ bank_connection : "possède"
    bank_connection ||--o{ bank_connection_authorization : "autorise"
    bank_connection_authorization o|--o| bank_connection_authorization : "remplace"
    app_user ||--o{ financial_account : "possède"
    bank_connection o|--o{ financial_account : "découvre"
    bank_connection ||--o{ sync_job : "synchronise"
    bank_connection_authorization ||--o{ sync_job : "permet"
    financial_account ||--o{ balance_snapshot : "mesure"
    sync_job o|--o{ balance_snapshot : "observe"
    app_user ||--o{ source_file : "dépose"
    app_user ||--o{ import_job : "lance"
    financial_account ||--o{ import_job : "reçoit"
    source_file ||--o{ import_job : "est traité par"
    import_job ||--o| import_mapping : "utilise"
    import_mapping ||--o{ column_mapping : "détaille"
    import_job ||--o{ rejected_import_row : "rejette"
    app_user o|--o{ category : "personnalise"
    category o|--o{ category : "a pour enfant"
    app_user ||--o{ transaction_record : "possède"
    financial_account ||--o{ transaction_record : "contient"
    category ||--o{ transaction_record : "classe"
    import_job o|--o{ transaction_record : "importe"
    source_file o|--o{ transaction_record : "source"
    sync_job o|--o{ transaction_record : "synchronise"
    transaction_record ||--o{ category_prediction : "reçoit"
    category ||--o{ category_prediction : "est proposée"
    category_prediction o|--o| transaction_record : "est appliquée"
    transaction_record ||--o| internal_transfer : "sortie"
    transaction_record ||--o| internal_transfer : "entrée"
```

## C. Planification financière

Un plan mensuel décrit les revenus et enveloppes budgétaires. Sa capacité
d'épargne peut être allouée à plusieurs objectifs. Les contributions réelles
restent séparées des scénarios, qui servent uniquement à comparer des
hypothèses sans modifier le plan.

```mermaid
erDiagram
    app_user {
        UUID id PK
    }
    category {
        UUID id PK
    }
    transaction_record {
        UUID id PK
    }
    monthly_plan {
        UUID id PK
        UUID user_id FK
    }
    planned_income {
        UUID id PK
        UUID monthly_plan_id FK
    }
    budget_allocation {
        UUID id PK
        UUID monthly_plan_id FK
        UUID category_id FK
    }
    financial_goal {
        UUID id PK
        UUID user_id FK
    }
    goal_allocation {
        UUID id PK
        UUID monthly_plan_id FK
        UUID financial_goal_id FK
    }
    goal_contribution {
        UUID id PK
        UUID financial_goal_id FK
        UUID transaction_id FK
    }
    goal_scenario {
        UUID id PK
        UUID financial_goal_id FK
    }

    app_user ||--o{ monthly_plan : "planifie"
    monthly_plan ||--o{ planned_income : "prévoit"
    monthly_plan ||--o{ budget_allocation : "répartit"
    category ||--o{ budget_allocation : "catégorise"
    app_user ||--o{ financial_goal : "définit"
    monthly_plan ||--o{ goal_allocation : "alloue"
    financial_goal ||--o{ goal_allocation : "reçoit"
    financial_goal ||--o{ goal_contribution : "cumule"
    transaction_record o|--o| goal_contribution : "justifie"
    financial_goal ||--o{ goal_scenario : "simule"
```

## D. Intelligence financière

Le périmètre d'une prévision est conservé dans `forecast_run_account`, même si
le calcul échoue. Une évaluation produite devient immuable et reste explicable
par ses dimensions et indicateurs. Les recommandations actives doivent avoir
au moins une justification reliée à un indicateur de la même évaluation.

```mermaid
erDiagram
    app_user {
        UUID id PK
    }
    monthly_plan {
        UUID id PK
    }
    financial_account {
        UUID id PK
    }
    financial_goal {
        UUID id PK
    }
    forecast_run {
        UUID id PK
        UUID user_id FK
        UUID monthly_plan_id FK
    }
    forecast_run_account {
        UUID forecast_run_id PK, FK
        UUID financial_account_id PK, FK
    }
    wellness_evaluation {
        UUID id PK
        UUID user_id FK
        UUID monthly_plan_id FK
        UUID forecast_run_id FK
    }
    wellness_evaluation_account {
        UUID wellness_evaluation_id PK, FK
        UUID financial_account_id PK, FK
    }
    evaluation_dimension {
        UUID id PK
        UUID wellness_evaluation_id FK
    }
    evaluation_indicator {
        UUID id PK
        UUID evaluation_dimension_id FK
    }
    personalized_recommendation {
        UUID id PK
        UUID user_id FK
        UUID wellness_evaluation_id FK
    }
    recommendation_justification {
        UUID id PK
        UUID recommendation_id FK
        UUID evaluation_indicator_id FK
    }
    financial_alert {
        UUID id PK
        UUID user_id FK
        UUID forecast_run_id FK
        UUID monthly_plan_id FK
        UUID financial_goal_id FK
        UUID financial_account_id FK
        UUID recommendation_id FK
    }
    user_notification {
        UUID id PK
        UUID user_id FK
        UUID financial_alert_id FK
        UUID recommendation_id FK
    }

    app_user ||--o{ forecast_run : "demande"
    monthly_plan o|--o{ forecast_run : "cadre"
    forecast_run ||--|{ forecast_run_account : "fige le périmètre"
    financial_account ||--o{ forecast_run_account : "est inclus"
    app_user ||--o{ wellness_evaluation : "reçoit"
    monthly_plan o|--o{ wellness_evaluation : "alimente"
    forecast_run o|--o{ wellness_evaluation : "alimente"
    wellness_evaluation ||--o{ wellness_evaluation_account : "couvre"
    financial_account ||--o{ wellness_evaluation_account : "est évalué"
    wellness_evaluation ||--|{ evaluation_dimension : "décompose"
    evaluation_dimension ||--|{ evaluation_indicator : "explique"
    app_user ||--o{ personalized_recommendation : "reçoit"
    wellness_evaluation ||--o{ personalized_recommendation : "génère"
    personalized_recommendation ||--|{ recommendation_justification : "justifie"
    evaluation_indicator ||--o{ recommendation_justification : "étaye"
    app_user ||--o{ financial_alert : "reçoit"
    forecast_run o|--o{ financial_alert : "déclenche"
    monthly_plan o|--o{ financial_alert : "déclenche"
    financial_goal o|--o{ financial_alert : "déclenche"
    financial_account o|--o{ financial_alert : "déclenche"
    personalized_recommendation o|--o| financial_alert : "accompagne"
    app_user ||--o{ user_notification : "reçoit"
    financial_alert o|--o{ user_notification : "notifie"
    personalized_recommendation o|--o{ user_notification : "notifie"
```

## Lecture des cardinalités

- `||` : exactement un ;
- `o|` : zéro ou un ;
- `|{` : un ou plusieurs ;
- `o{` : zéro ou plusieurs.

Les triggers SQL complètent ces relations pour les invariants qui dépassent
une simple clé étrangère : cohérence utilisateur/devise, provenance des
transactions, virements internes, allocations, immutabilité des évaluations et
activation des recommandations.
