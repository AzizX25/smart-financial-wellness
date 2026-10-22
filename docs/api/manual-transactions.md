# Transaction management

All routes require an ACTIVE platform user and ACTIVE authentication identity.
Ownership comes only from the authenticated principal. The browser uses the main
backend's shared authenticated API client, never the mock bank.

- `POST /api/transactions`: create a `MANUAL` transaction on an owned ACTIVE
  personal (`MANUAL`) financial account. Request: `accountId`, UUID
  `clientIdempotencyKey`, `bookingDate`, `description`, positive decimal `amount`,
  `direction` (`DEBIT`/`CREDIT`) and nullable `categoryId`.
- `PUT /api/transactions/{id}`: replace those editable financial fields, with
  the current `version`; account, currency, ownership and provenance are not editable.
- Transaction deletion is not available, for any source. There is no DELETE
  controller route or service mutation; authenticated DELETE attempts return a safe
  JSON HTTP 405 with an `Allow` header. The UI has no transaction deletion controls.
- Existing `GET /api/transactions` adds `sourceType`, `version`, `amountExact` and `localOverride`.
  Keep using `amountExact` (a decimal string) when editing, not the numeric display value.

Mutation responses contain `id`, `accountId`, `sourceType`, `bookingDate`,
`description`, exact decimal-string `amount`, `direction`, `currency`, `categoryId`
and the resulting `version`. POST returns 201 and PUT 200. Unknown or
foreign resources return 404. Stale versions, protected links
and reused keys with different content return 409; invalid input returns 400.

Amounts have at most four fractional digits and 15 integer digits. Dates range from
1900 to today in the user's configured timezone. Currency is derived from the
account. Visible active categories must match direction and ownership.

Repeated/concurrent identical creates reuse the same UUID key and row. Reusing
that key for a legacy removed entry cannot resurrect the operation. Entries linked
to goal contributions or internal transfers cannot be edited through these routes.
PUT supports MANUAL, FILE_IMPORT (including reviewed receipts imported as
CSV), and BANK_SYNC. Imported identifiers, file/row provenance and bank source
identifiers are never editable. V24 retains original file values in `source_details`
before the first correction. For bank records, it stores the latest provider values
separately from locally corrected values (`local_override`). Bank synchronization
continues updating provider status and source details, but does not replace local
financial corrections. Removed bank identities are locked and skipped on replay;
CSV import also retains removed file/row identities. Neither replay resurrects rows.

Every history row has a category-approval button using the normal
`PATCH /api/transactions/{id}/category` endpoint. Choose a category first if none is
assigned. Approval confirms an existing rule/AI category as a user decision; it never
automatically accepts a pending AI prediction. Existing explicit suggestion acceptance
remains available separately. Editing invalidates previous merchant suggestions; in-flight AI
batch responses are checked against the captured transaction version.

## Synchronized approval views

The transaction screen opens on **À approuver**, with **Approuvées** and
**Toutes les transactions** available beside it. These are server-paginated views
of the same canonical records, not duplicated ledgers or new database tables.
`GET /api/transactions?approved=false` selects pending confirmations;
`approved=true` selects approved assignments. Omitting the parameter preserves the
existing complete-history behavior. All account, category, date, direction, search,
ownership and pagination filters still apply.

The response includes `categoryApproved`. A user assignment (`USER`) or an explicitly
accepted ML, web or private-memory suggestion (`ML`, `LLM`, `USER_MEMORY`) is approved.
SQL integrity already requires accepted predictions for those suggestion sources.
Automatic `RULES` assignments and uncategorized rows await confirmation. The history
dropdown keeps a draft until **Approuver la catégorie** is clicked. Selecting
**Non catégorisée** on an assigned row requires **Confirmer le retrait** and returns
the row to pending. Manual assignment through the other existing workflows is also
a confirmation.
Approval does not delete a transaction or change financial totals.

The page refetches after category mutations, accepted suggestions, import, edits,
removal and bank synchronization. Switching views requests fresh server data; stale
responses cannot overwrite the current view. If confirmation empties the last page,
pagination returns to the last valid page. Load failures are not displayed as empty
transaction history. Existing approvals survive normal synchronization.

Approval-view files changed:

- Under `backend/src/main/java/com/azizx25/smartfinancialwellness/`:
  `bank/LocalBankDataService.java`, `bank/api/LocalBankDataController.java`,
  `bank/api/LocalBankDtos.java`, `bank/persistence/TransactionRecord.java`,
  `bank/persistence/TransactionRecordRepository.java`.
- Under `backend/src/test/java/com/azizx25/smartfinancialwellness/`:
  `ManualTransactionApiIntegrationTests.java`, `BankSynchronizationIntegrationTests.java`,
  `MerchantRecognitionIntegrationTests.java`.
- Under `frontend/src/`: `pages/TransactionsPage.jsx`, `pages/TransactionsPage.test.jsx`,
  `pages/TransactionsManagement.test.jsx`, `pages/TransactionsMerchantRecognition.test.jsx`,
  `services/financialApi.js`, `services/financialApi.test.js` (new).
- This guide, `docs/api/manual-transactions.md`.

Approval-view validation: the four focused backend classes
`ManualTransactionApiIntegrationTests,BankSynchronizationIntegrationTests,CategoryIntegrationTests,MerchantRecognitionIntegrationTests`
passed **39 tests, no failures/errors/skips** using the Maven wrapper and real
PostgreSQL/Flyway. Merchant tests were then rerun successfully (**10 tests**) with
additional ML, LLM and private-memory approval assertions. Complete frontend
`npm test`: **150 passed in 24 files**; `npm run lint`, `npm run build`,
Compose configuration and `git diff --check` passed. No migration was created or
modified for the views, and no full backend-suite claim is made.

Legacy tombstones created before deletion was retired retain V23's `deleted_at`,
categorization history and AI evidence. Existing rows are not restored, reset or
physically removed by disabling deletion. JPA active-row restrictions and explicit native
query predicates exclude tombstones from transactions, category summaries, Salary
Planning history, score inputs and Forecast cash flow. Already-produced immutable
scores/forecasts and saved plan recommendations are not rewritten by a mutation.
Current insights are refreshed through the existing workflows.

Local transaction mutations affect income/expense flows, not bank-reported balances or
declared reference balances. No money moves at a bank. The UI can reuse the existing
owned account-creation API to create a personal account with zero declared balance.

## Transaction deletion retired

Creation, local corrections, category approval/removal and synchronization remain
available. Removing a category is not removing a transaction. All existing migrations
(including already-applied V23/V24) remain unchanged; no new migration is necessary.
Disposable integration fixtures retain regression coverage for legacy tombstones,
but no application API creates new ones. Tests verify DELETE returns 405 for manual,
CSV and bank rows and leaves their values, balances and analytics unchanged.

Files changed to retire deletion:

- Under `backend/src/main/java/com/azizx25/smartfinancialwellness/`:
  `transaction/api/ManualTransactionController.java`, `transaction/ManualTransactionService.java`,
  `bank/persistence/TransactionRecord.java`, `common/web/GlobalExceptionHandler.java`.
- Under `backend/src/test/java/com/azizx25/smartfinancialwellness/`:
  `ManualTransactionApiIntegrationTests.java`, `BankSynchronizationIntegrationTests.java`,
  `transaction/ManualTransactionServiceTests.java`.
- Under `frontend/src/`: `pages/TransactionsPage.jsx`, `pages/TransactionsManagement.test.jsx`,
  `services/manualTransactionApi.js`, `services/manualTransactionApi.test.js`.
- This guide, `docs/api/manual-transactions.md`.

Deletion-retirement validation: the Maven wrapper ran
`ManualTransactionApiIntegrationTests,BankSynchronizationIntegrationTests,ManualTransactionServiceTests,CategoryIntegrationTests`:
**30 tests, 0 failures, 0 errors, 0 skipped**, with disposable PostgreSQL/Flyway and
Hibernate validation. Complete frontend `npm test`: **149 tests passed in 24 files**;
`npm run lint`, `npm run build`, Compose configuration and `git diff --check` passed.
No existing migration or private environment file was edited, and no live user
records were restored or removed.

## Historical all-source increment inventory (V24)

This extension changes only the following files (paths relative to the repository):

- `backend/src/main/resources/db/migration/V24__support_source_preserving_transaction_management.sql` (new).
- Under `backend/src/main/java/com/azizx25/smartfinancialwellness/`:
  `transaction/ManualTransactionService.java`, `bank/BankSynchronizationStore.java`,
  `bank/LocalBankDataService.java`, `bank/api/LocalBankDtos.java`,
  `bank/persistence/TransactionRecord.java`, `bank/persistence/TransactionRecordRepository.java`.
- Under `backend/src/test/java/com/azizx25/smartfinancialwellness/`:
  `ManualTransactionApiIntegrationTests.java`, `transaction/ManualTransactionServiceTests.java`,
  `BankSynchronizationIntegrationTests.java`, `DatabaseMigrationTests.java`,
  `BankConnectionMigrationUpgradeTests.java`, `CategorizationReviewMigrationTests.java`,
  `GoalMigrationIntegrationTests.java`. The last four only update migration expectations.
- Under `frontend/src/`: `components/ManualTransactionForm.jsx`,
  `components/ManualTransactionForm.test.jsx`, `pages/TransactionsPage.jsx`,
  `pages/TransactionsManagement.test.jsx`.
- `docs/api/manual-transactions.md` (this guide).

Focused backend command (from `backend/`):

```powershell
.\mvnw.cmd "-Dtest=ManualTransactionApiIntegrationTests,ManualTransactionServiceTests,BankSynchronizationIntegrationTests,DatabaseMigrationTests,BankConnectionMigrationUpgradeTests,CategorizationReviewMigrationTests,GoalMigrationIntegrationTests,CsvImportApiIntegrationTests,CategoryIntegrationTests,MerchantRecognitionIntegrationTests" "-Dlogging.level.root=ERROR" "-Ddebug=false" "-Dspring.jpa.show-sql=false" test
```

Verified Surefire results: **69 tests, 0 failures, 0 errors, 0 skipped**, including
V24 application and Hibernate schema validation with disposable PostgreSQL.
Frontend focused transaction tests: **30 passed**. Complete frontend `npm test`:
**141 passed in 23 files**. `npm run lint`, `npm run build`,
`docker compose --env-file .env config --quiet` and `git diff --check` passed.
These tests use no real AI/web providers. No full backend verification is claimed.

Local deployment: `docker compose up -d --no-deps --build backend frontend`
completed successfully. Only those services were rebuilt/recreated. Flyway applied
V24 to the existing database, Hibernate validation/startup succeeded,
`http://localhost:8082/actuator/health` returned `UP`, and the frontend on port 5173
returned HTTP 200. No volumes or user financial records were deleted. Authenticated
CRUD was verified in isolated integration tests, not by mutating the live demo account.

## Original manual-only implementation inventory

Backend paths below are relative to `backend/src/main/java/com/azizx25/smartfinancialwellness/`:

- Created: `transaction/ManualTransactionService.java`, `transaction/ManualTransactionException.java`,
  `transaction/api/ManualTransactionController.java`, `transaction/api/ManualTransactionDtos.java`.
- Updated persistence/read DTOs: `bank/persistence/TransactionRecord.java`,
  `bank/persistence/TransactionRecordRepository.java`, `bank/LocalBankDataService.java`,
  `bank/api/LocalBankDtos.java`.
- Updated category invalidation/version checks: `category/CategoryService.java`,
  `category/CategorizationReviewService.java`, `category/MerchantRecognitionPersistence.java`,
  `category/MerchantRecognitionService.java`.
- Updated native active-row filters: `category/persistence/CategoryRepository.java`,
  `salary/persistence/SalaryHistoryRepository.java`, `score/persistence/FinancialScoreInputRepository.java`,
  `forecast/persistence/ForecastFinancialDataRepository.java`.
- Created migration: `backend/src/main/resources/db/migration/V23__support_manual_transaction_removal.sql`.
  V1–V22 are unchanged by this increment.

Backend test paths are relative to `backend/src/test/java/com/azizx25/smartfinancialwellness/`:

- Created: `ManualTransactionApiIntegrationTests.java`, `transaction/ManualTransactionServiceTests.java`.
- Updated only migration expectations: `DatabaseMigrationTests.java`,
  `BankConnectionMigrationUpgradeTests.java`, `CategorizationReviewMigrationTests.java`,
  `GoalMigrationIntegrationTests.java`.

Frontend paths are relative to `frontend/src/`:

- Created: `components/ManualTransactionForm.jsx`, `components/ManualTransactionForm.test.jsx`,
  `pages/TransactionsManagement.test.jsx`, `services/manualTransactionApi.js`,
  `services/manualTransactionApi.test.js`, `services/manualTransactionValidation.js`.
- Updated: `pages/TransactionsPage.jsx`, `design-system.css` (manual-operation styles only).

This guide is the only new documentation file for the increment. The report remains paused.

## Validation

The validation counts below record the earlier manual-only increment, not the
expanded all-source increment. V24 is forward-only; V1–V23 remain unchanged.

Focused PostgreSQL, migration, categorization and merchant regressions:

```powershell
# From backend/
.\mvnw.cmd "-Dtest=ManualTransactionApiIntegrationTests,ManualTransactionServiceTests,DatabaseMigrationTests,BankConnectionMigrationUpgradeTests,CategorizationReviewMigrationTests,GoalMigrationIntegrationTests,CsvImportApiIntegrationTests,CategoryIntegrationTests,MerchantRecognitionIntegrationTests" "-Dlogging.level.root=ERROR" "-Ddebug=false" "-Dspring.jpa.show-sql=false" test
```

Recorded result for this increment: **55 tests, 0 failures, 0 errors, 0 skipped**.
The final normalized-description and credit/direction checks were rechecked with the two
new manual classes: **10 tests passed**. Spring context/Hibernate validation and V23
Flyway application succeeded with disposable PostgreSQL Testcontainers.

Frontend: `npm test` passed **139 tests in 23 files**; `npm run lint` and
`npm run build` passed. `git diff --check` passed. No real AI/web/SMTP calls are
required by these automated tests. No full backend verification claim is made.

No dependencies were added. Unrelated work and pre-existing migrations were preserved;
nothing was staged, committed or pushed.
