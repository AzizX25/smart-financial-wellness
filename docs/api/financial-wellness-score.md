# Financial Wellness Score API

This authenticated API produces immutable, deterministic score snapshots with
calculation version `financial-wellness-score-v1`. It is currency-isolated,
uses up to the six completed months before the user's local current month, and
never calls an LLM or converts currencies.

The score is a weighted average over available factors only. Savings rate is
mandatory, at least three completed months must contain positive eligible
income, and available base weight must reach 50. Missing factors are returned
with stable reasons rather than scored as zero. The current category taxonomy
does not identify essential expenses, so essential-expense burden and balance
resilience remain explicitly unavailable.

Eligible history includes final manual and file-imported transactions plus
`POSTED` bank transactions. Confirmed internal transfers, current/future-month
transactions, foreign currencies, and foreign users are excluded. Produced
snapshots and their factors are database-protected against update and deletion.
An identical input fingerprint returns the existing snapshot; changed
authoritative input creates a new snapshot.

## Endpoints

| Method and path | Responsibility |
| --- | --- |
| `POST /api/financial-scores/current?currency=TND` | Calculate current authoritative inputs and persist/return an idempotent snapshot when sufficient |
| `GET /api/financial-scores?currency=TND&page=0&size=12` | List owned persisted snapshots for one currency |
| `GET /api/financial-scores/{scoreId}` | Retrieve one owned snapshot; foreign identifiers return `404` |

Responses expose the calculation period, timezone, version, score band,
available base weight, and all six factor contracts. They never expose user,
account, or fingerprint identifiers. `INSUFFICIENT_DATA` is a successful,
non-persisted response with no fabricated numeric score.

The indicator is educational: it is neither a credit score, regulated
financial advice, nor a recommendation.
