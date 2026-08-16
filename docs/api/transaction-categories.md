# Transaction categorization API

This slice categorizes persisted transactions with explicit local rules and
manual user choices. It does not use AI, ML, an LLM, external lookups, or
currency conversion.

## Model and precedence

System categories are shared and read-only. Personal categories are scoped to
the authenticated user and have case-insensitively unique trimmed names.
Categories declare one controlled nature: `EXPENSE`, `INCOME`, `TRANSFER`, or
`NEUTRAL`. Transaction amounts remain positive decimals and direction remains
the separate `DEBIT` or `CREDIT` value.

Automatic matching uses normalized stored descriptions and counterparties.
Rules are ordered by exact match before contains match, then descending
priority, descending pattern length, and stable rule code. A transaction with a
manual assignment is never selected by an automatic run. No match leaves the
transaction uncategorized.

Each automatic or manual change writes append-only
`transaction_category_history` and a safe audit event. The history stores only
transaction/category/rule references, assignment sources, actor, and timestamp;
it contains no credentials, provider authorization, or HTTP headers.

New bank-synchronized transactions are categorized after their atomic
persistence succeeds. A categorization failure is isolated from synchronization
so already-synchronized financial data and the application session remain valid.

## Protected endpoints

All endpoints derive ownership exclusively from the active authenticated
principal.

| Method and path | Responsibility |
| --- | --- |
| `GET /api/categories` | List active system and owned personal categories |
| `POST /api/categories` | Create an owned personal category |
| `PATCH /api/categories/{categoryId}` | Update an owned personal category |
| `DELETE /api/categories/{categoryId}` | Delete an unused owned personal category |
| `PATCH /api/transactions/{transactionId}/category` | Assign or correct a category; send `null` to remove it |
| `POST /api/categorization-runs` | Apply rules to the user's uncategorized transactions |
| `GET /api/categories/summary?month=YYYY-MM` | Return monthly totals and counts separated by currency and category nature |
| `GET /api/transactions` | Filter with `categoryId` or `uncategorized=true` in addition to existing filters |

The summary never combines currencies and reports transfers separately from
expenses. Missing and cross-user resources are indistinguishable. Controlled
codes include `CATEGORY_NOT_FOUND`, `CATEGORY_NAME_ALREADY_EXISTS`,
`CATEGORY_IN_USE`, `CATEGORY_READ_ONLY`, `CATEGORY_NOT_ALLOWED`, and
`TRANSACTION_NOT_FOUND`.

Synchronization is currently manual through **Actualiser**. Automatic refresh
based on a configurable local-data freshness threshold remains deliberately
deferred.
