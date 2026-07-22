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

The mock-bank module does not exist yet. Its planned V1-V4 migrations are not
placed in this directory and must start an independent Flyway history when the
module is created:

1. customers, accounts, balances, and transactions;
2. OAuth client and read-only consent;
3. hashed authorization codes and rotating/revocable token hashes;
4. strictly fictitious demonstration data.

## Validation expectations

- Run the complete V1-V10 sequence on a clean PostgreSQL database.
- Test both valid writes and expected database rejections with Testcontainers.
- Run `mvn test` from `backend` after migration changes.
- Never edit an applied migration; add a new forward-only migration.
