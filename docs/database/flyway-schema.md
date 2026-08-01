# PostgreSQL and Flyway schema

## Canonical location

Backend migrations live only in:

```text
backend/src/main/resources/db/migration
```

The backend uses PostgreSQL schema `smart_financial_wellness`. Both Flyway and
Hibernate are configured for that schema, and `spring.jpa.hibernate.ddl-auto`
remains `validate`.

## Migration order

| Migration | Responsibility | Tables |
| --- | --- | ---: |
| `V1__initialize_application_schema.sql` | Existing technical schema initialization; preserved unchanged | 0 |
| `V2__create_users_and_preferences.sql` | Users, profile settings, and notification preferences | 4 |
| `V3__create_local_authentication.sql` | Local email identity, password hash, refresh sessions, and action tokens | 4 |
| `V4__create_consents_audit_and_personal_data_requests.sql` | Consent history, append-only audit, export and deletion requests | 5 |
| `V5__create_bank_connections_and_financial_accounts.sql` | Read-only bank consent, financial accounts, sync jobs, and balance history | 5 |
| `V6__create_file_imports.sql` | CSV/XLSX/XLS files, mapping, import attempts, and rejected rows | 5 |
| `V7__create_categories_and_canonical_transactions.sql` | UUID categories, canonical transactions, prediction trace, and internal transfers | 4 |
| `V8__create_salary_planning_and_goals.sql` | Plans, budgets, goals, contributions, allocations, and saved scenarios | 7 |
| `V9__create_forecasts_and_wellness_evaluations.sql` | Forecast scope/results and immutable explainable wellness evaluations | 6 |
| `V10__create_recommendations_alerts_and_notifications.sql` | Justified recommendations, alerts, and in-app notifications | 4 |

The sequence creates 44 backend application tables. There is no artificial
legacy transaction representation and no signed-amount conversion migration.

Four presentation-oriented diagrams derived from these migrations are
available in the canonical [entity-relationship documentation](entity-relationship-diagrams.md).

## Security and data rules

- Secrets, passwords, authorization codes, and tokens are never stored raw.
- Bank authorizations contain read-only scopes and only a secret-manager
  reference.
- A partial unique index permits one `ACTIVE` authorization per connection;
  time-based expiry is checked when an authorization or sync job is written,
  not in an index predicate.
- User ownership, account currency, import provenance, bank-sync provenance,
  private categories, goals, forecasts, evaluations, alerts, and notifications
  are enforced with checks, foreign keys, indexes, and focused cross-table
  triggers.
- `audit_log_entry` and `consent_event` reject updates and deletes.
- Produced wellness evaluations reject mutation except for the controlled
  transition from `PRODUCED` to `OBSOLETE`.
- Recommendations are created as drafts and cannot become active without a
  justification tied to the same evaluation.
- Cascades are limited to children inside a clear aggregate. There is no global
  cascade from `app_user`.

## AI and ML traceability

- `import_mapping`: method, model name/version, confidence, and user
  confirmation;
- `category_prediction`: method, model name/version, confidence, and production
  time;
- `transaction_record`: applied category, category source, and optional applied
  prediction;
- `forecast_run`: model and pipeline versions, horizon, training date, data
  cutoff, coverage, and error metrics;
- `personalized_recommendation`: generation method, rules/model/LLM versions,
  and confidence.

## Mock-bank migrations

Mock-bank migrations live only in:

```text
mock-bank/src/main/resources/db/migration
```

The mock bank uses its own PostgreSQL database, schema `mock_bank`, and Flyway
history table. It has no database dependency or foreign key to the main
application.

| Migration | Responsibility | Tables |
| --- | --- | ---: |
| `V1__create_mock_bank_schema.sql` | Synthetic customers, OAuth client metadata, accounts, positive directional transactions, read-only consents, and hashed authorization/token artifacts | 7 |
| `V2__seed_demo_data.sql` | Deterministic synthetic customers, client, accounts, and transactions used by local development and tests | 0 |

The seven tables are `bank_customer`, `oauth_client`, `bank_account`,
`bank_transaction`, `bank_consent`, `authorization_code`, and `access_token`.
Only the Actuator health endpoint is currently exposed. Account, transaction,
consent, authorization-code, and token HTTP APIs will be implemented later,
including a lightweight simulated read-only authorization flow.

## Validation expectations

- Run the complete V1-V10 sequence on a clean PostgreSQL database.
- Test both valid writes and expected database rejections with Testcontainers.
- Run `mvn test` from `backend` after migration changes.
- Run `mvn test` from `mock-bank` to validate its independent V1-V2 history,
  seven mappings, deterministic fixtures, and PostgreSQL constraints.
- Never edit an applied migration; add a new forward-only migration.
