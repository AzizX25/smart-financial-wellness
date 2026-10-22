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
| `V11__secure_mock_bank_connections.sql` | Simulated provider-token lifecycle and current mock-bank link uniqueness | 0 |
| `V12__persist_bank_synchronization.sql` | Persistent provider-scoped accounts, balances, canonical transactions, and synchronization state | 0 |
| `V13__add_deterministic_transaction_categorization.sql` | French category metadata, deterministic rules, and append-only correction history | 2 |

The sequence creates 46 backend application tables. There is no artificial
legacy transaction representation and no signed-amount conversion migration.

Four presentation-oriented diagrams derived from these migrations are
available in the canonical [entity-relationship documentation](entity-relationship-diagrams.md).

## Security and data rules

- Application passwords, refresh tokens, action tokens, and bank passwords are
  never stored raw.
- For the simulated development bank only, V11 permits the short-lived provider
  access JWT to be stored with a read-only authorization. It is never exposed
  publicly and is cleared on disconnect or provider authentication rejection;
  production provider secrets remain a future secret-manager concern.
- A partial unique index permits one `ACTIVE` authorization per connection;
  time-based expiry is checked when an authorization or sync job is written,
  not in an index predicate.
- V12 serializes synchronization starts per connection, permits only one
  `RUNNING` sync job, and keeps stable provider identities for idempotent account
  and transaction upserts. It adds no domain table.
- V13 keeps the existing UUID category model, adds controlled nature and safe
  presentation metadata, seeds conservative deterministic rules, and records
  every automatic or manual assignment in append-only
  `transaction_category_history`. Database checks keep transaction direction,
  assignment source, rule, prediction, and category consistent.
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
- `transaction_record`: applied category, category source, optional applied
  prediction, optional deterministic rule, and categorization timestamp;
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
| `V3__add_demo_customer_credentials.sql` | Unique demo usernames and BCrypt password hashes | 0 |

The seven tables are `bank_customer`, `oauth_client`, `bank_account`,
`bank_transaction`, `bank_consent`, `authorization_code`, and `access_token`.
The Actuator health/info endpoints and login are public. Four customer-scoped,
read-only account list, account metadata, balance, and transaction endpoints
require a signed bearer JWT whose subject matches the path customer. Their HTTP
responses use public records rather than persistence entities, and ownership
mismatches use the same `404 Account not found` response as absent accounts.
OAuth consent, authorization-code, persisted token, payment, and transfer HTTP
APIs remain deferred.

## Validation expectations

- Run the complete backend V1-V12 sequence on a clean PostgreSQL database.
- Test both valid writes and expected database rejections with Testcontainers.
- Run `mvn test` from `backend` after migration changes.
- Run `mvn test` from `mock-bank` to validate its independent V1-V4 history,
  seven mappings, deterministic fixtures, and PostgreSQL constraints.
- Never edit an applied migration; add a new forward-only migration.
