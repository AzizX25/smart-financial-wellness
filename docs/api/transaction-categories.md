# Transaction categorization API

This slice categorizes persisted transactions with explicit local rules,
private user choices, the frozen synthetic merchant model, and a bounded web
plus local-LLM fallback. No automatic result changes a transaction without an
explicitly safe rule or the user's confirmation. It performs no currency
conversion.

## Model and precedence

System categories are shared and read-only. Personal categories are scoped to
the authenticated user and have case-insensitively unique trimmed names.
Categories declare one controlled nature: `EXPENSE`, `INCOME`, `TRANSFER`, or
`NEUTRAL`. Transaction amounts remain positive decimals and direction remains
the separate `DEBIT` or `CREDIT` value.

The order is: existing manual choice, deterministic rules, exact private user
merchant memory, global exact/fuzzy aliases, the existing ML model above its
frozen threshold, bounded web search plus local reasoning, then abstention.
Rules are ordered by exact match before contains match, then descending
priority, descending pattern length, and stable rule code. A transaction with a
manual assignment is never selected by an automatic run.

Every owned uncategorized transaction remains in the review feed with one
state:

- `SUGGESTION_PENDING`: a reliable pending suggestion awaits accept/reject;
- `NEEDS_REVIEW`: no reliable result exists, with zero to three ordered closed-
  taxonomy candidates as optional hints;
- `EXCLUDED`: the current pipeline cannot analyze it and returns a reason such
  as `NOT_POSTED`, `REVERSED`, `INTERNAL_TRANSFER`,
  `UNSUPPORTED_CURRENCY`, or `CREDIT_NOT_SUPPORTED`.

Candidates never include a public confidence score and are never preselected or
applied. Abstention is preferable to forcing an unreliable category; manual
categorization remains available when the transaction/category direction rules
allow it. The AI pipeline currently accepts posted, non-transfer debit
transactions in TND. Other currencies, credits, and unavailable provider states
remain visible with an explicit manual fallback.

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
| `POST /api/categorization-runs/merchant-recognition` | Generate pending merchant suggestions or explicit review/exclusion states |
| `GET /api/categorization-reviews?page=0&size=20` | Page through every owned uncategorized transaction and its actionable review state |
| `GET /api/merchant-suggestions` | List the user's pending reliable suggestions |
| `POST /api/transactions/{transactionId}/merchant-suggestions/{predictionId}/accept` | Accept one owned pending suggestion |
| `POST /api/transactions/{transactionId}/merchant-suggestions/{predictionId}/reject` | Reject it without categorizing or learning a mapping |
| `GET /api/merchant-memories` | List the authenticated user's private learned merchant mappings |
| `DELETE /api/merchant-memories/{memoryId}` | Forget one owned mapping without changing historical assignments |
| `GET /api/categories/summary?month=YYYY-MM` | Return monthly totals and counts separated by currency and category nature |
| `GET /api/transactions` | Filter with `categoryId` or `uncategorized=true` in addition to existing filters |

The summary never combines currencies and reports transfers separately from
expenses. Missing and cross-user resources are indistinguishable. Controlled
codes include `CATEGORY_NOT_FOUND`, `CATEGORY_NAME_ALREADY_EXISTS`,
`CATEGORY_IN_USE`, `CATEGORY_READ_ONLY`, `CATEGORY_NOT_ALLOWED`, and
`TRANSACTION_NOT_FOUND`.

## Private merchant memory and privacy

A mapping is created or updated only after an explicit manual assignment or an
accepted suggestion. Rejection never learns. Matching is exact and scoped by
user, sanitized merchant key, direction, and currency; it creates only a
pending `USER_MEMORY` suggestion and avoids ML, web, and LLM calls. A correction
updates only that user's mapping, and foreign memory identifiers are returned
as not found.

Memory stores no amount, balance, account identifier, email, phone-like value,
IBAN, raw reference, or unsanitized description. The AI request receives only
an opaque request key, sanitized merchant text, direction, currency, and
controlled contract versions. Web evidence attached to an accepted/rejected
V19 suggestion remains immutable. Search/AI calls run outside PostgreSQL
transactions; validated batch persistence is atomic afterward.

Synchronization is currently manual through **Actualiser**. Automatic refresh
based on a configurable local-data freshness threshold remains deliberately
deferred.
