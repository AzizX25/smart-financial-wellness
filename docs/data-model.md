# Smart Financial Wellness data model

## Backend database

The core backend owns 44 application tables in the
`smart_financial_wellness` PostgreSQL schema. Flyway is the schema owner;
Hibernate only validates mappings as application features are implemented.

### Users, settings, and authentication

| Table | Responsibility |
| --- | --- |
| `app_user` | Business identity, essential profile information, and account status |
| `interface_preference` | Locale, time zone, theme, and accessibility settings |
| `financial_preference` | Currency and financial display settings |
| `notification_preference` | Per-event and per-channel notification choices |
| `authentication_identity` | Unique normalized local email identity |
| `password_credential` | Adaptive password hash and hashing metadata |
| `refresh_session` | Hashed, rotating, revocable refresh-token session |
| `account_action_token` | Hashed single-use email verification and password-reset token |

Access JWTs are stateless and are not persisted. Passwords, refresh tokens,
and account-action tokens are never stored in plaintext.

### Consent, audit, and personal data

| Table | Responsibility |
| --- | --- |
| `user_consent` | Current application-consent state |
| `consent_event` | Immutable consent history |
| `audit_log_entry` | Append-only sensitive-operation audit log |
| `personal_data_request` | Access, export, rectification, and deletion workflow |
| `personal_data_export` | Temporary downloadable export metadata |

Deleting an `app_user` never cascades through every financial aggregate. The
application must execute a dedicated deletion or anonymization workflow.

### Bank connection and accounts

| Table | Responsibility |
| --- | --- |
| `bank_connection` | User-owned connection to the simulated bank |
| `bank_connection_authorization` | Read-only authorization history and simulated provider access token |
| `financial_account` | Manual or bank-discovered account |
| `sync_job` | Traceable account, balance, and transaction synchronization |
| `balance_snapshot` | Account balance observed at a point in time |

Only one authorization may be `ACTIVE` for a connection. It contains read-only
scopes and, for this deliberately simple development integration, the
short-lived mock-bank access JWT. The token is never returned by a public API
and is cleared on disconnect or provider authentication rejection. The bank
password is never stored. A production provider integration would replace this
storage with a dedicated secret manager. Revoked or expired authorizations
cannot start a sync.

V11 adds current-connection uniqueness: one current mock-bank connection per
application user, and one current provider customer link across application
users. Disconnected records remain as history after their provider identity and
token are cleared.

V12 adds persistent synchronization state without adding tables. A connection
records the current sync state, last attempt, last success, and a bounded safe
failure code. Each sync job records created/updated counters. Synchronized
accounts retain provider-scoped identity, current and available balances,
provider status, and first/last synchronization timestamps. Canonical
`BANK_SYNC` transactions retain their provider status and first/last import
timestamps. Re-running a sync updates records using stable provider identifiers;
the transaction uniqueness remains `(financial_account_id,
external_transaction_id)`. Provider retrieval completes before the atomic
database write, and failed attempts preserve the last successful local data.

### File imports and canonical transactions

| Table | Responsibility |
| --- | --- |
| `source_file` | Canonical CSV, XLSX, or XLS file metadata and SHA-256 digest |
| `import_job` | An import attempt for one file and account |
| `import_mapping` | Mapping method, model trace, confidence, and user confirmation |
| `column_mapping` | Source-to-canonical field mapping |
| `rejected_import_row` | Safe row-level import diagnostics |
| `category` | UUID-keyed system or private category |
| `transaction_record` | Positive canonical transaction with explicit direction |
| `category_prediction` | Immutable prediction evidence without a selected flag |
| `categorization_rule` | Versionable deterministic exact/contains rules over stored evidence |
| `transaction_category_history` | Append-only automatic and manual assignment history |
| `internal_transfer` | Same-user debit/credit pair between distinct accounts |

Canonical transactions use `NUMERIC(19,4)` amounts greater than zero and a
`DEBIT` or `CREDIT` direction. Provenance and idempotence depend on the source:

- `MANUAL`: optional user-scoped client idempotency key;
- `FILE_IMPORT`: unique account, source file, and source row number;
- `BANK_SYNC`: unique account and stable external transaction ID.

The non-unique fingerprint is retained for duplicate analysis. A transaction
stores the category actually applied, its source, categorization time, and
optionally the matching deterministic rule and immutable prediction trace.
Manual assignments clear automatic rule references and automatic runs lock and
select only uncategorized records, so later synchronization or reruns cannot
overwrite a user's correction.

### Planning, goals, forecasts, and recommendations

| Area | Tables |
| --- | --- |
| Salary planning | `monthly_plan`, `planned_income`, `budget_allocation` |
| Goals | `financial_goal`, `goal_allocation`, `goal_contribution`, `goal_scenario` |
| Forecasts | `forecast_run`, `forecast_run_account` |
| Wellness | `wellness_evaluation`, `wellness_evaluation_account`, `evaluation_dimension`, `evaluation_indicator` |
| Guidance | `personalized_recommendation`, `recommendation_justification`, `financial_alert`, `user_notification` |

Goal current amounts are calculated from the initial amount and contributions;
they are not stored. Saved goal scenarios preserve their assumptions without
changing real plans or contributions. Ad-hoc forecast simulations are
temporary API data and have no table.

`forecast_run_account` is populated when accounts are selected, before model
execution. Its reference data survives a failed run, while result columns may
remain null. Produced wellness evaluations and their account scope, dimensions,
and indicators are immutable. Active recommendations require a factual
justification from the same evaluation.

## Simulated bank boundary

The mock bank is a separate Spring Boot application and owns a separate
PostgreSQL database, `mock_bank` schema, and Flyway history. Its implemented
foundation contains seven tables:

- `bank_customer`;
- `oauth_client`;
- `bank_account`;
- `bank_transaction`;
- `bank_consent`;
- `authorization_code`;
- `access_token`.

V1 creates these tables, V2 supplies deterministic synthetic customers, OAuth
client metadata, accounts, and transactions, and V3 adds unique demo usernames
with BCrypt password hashes. `POST /api/auth/login` emits a short-lived signed
JWT; customer-scoped account, balance, and transaction endpoints require that
the JWT subject match the customer path. Public response records prevent
persistence identifiers and entities from crossing the service boundary,
while `404` ownership responses avoid account enumeration. OAuth consent,
authorization-code, persisted provider-token, payment, and transfer APIs remain
future work. There are no foreign keys between the mock-bank and backend
databases.
