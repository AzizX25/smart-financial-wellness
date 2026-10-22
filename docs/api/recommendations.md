# Financial recommendations

ACTIVE authenticated users generate educational recommendations from immutable
V21 score factors. Ownership and currency are server-scoped; currencies are never
converted. Recommendations never change financial data or score formulas.

## API

- `POST /api/recommendations/current?currency=TND`: idempotently generate a set;
  returns `status`, `currency`, `evaluationId`, `rulesVersion` and `items`.
- `GET /api/recommendations?currency=TND&page=0&size=12`: read saved recommendations
  and facts without recalculation; obsolete items are excluded.
- `PATCH /api/recommendations/{id}/status`: only `{ "status": "ACCEPTED" }`,
  `REJECTED` or `HIDDEN`. Foreign IDs return 404. Identical feedback is idempotent;
  another transition from a closed item returns 409.
- `POST /api/recommendations/{id}/explanation`: optional local explanation. Returns
  `source` (LOCAL_LLM or RULES), `text`, `factCode`, `rulesVersion`, `fallbackReason`.
  No explanation is persisted or changes facts.

Items include type, priority/status, title/message, currency/period, supporting
factor value/unit/score/explanation, navigation-only action and disclaimer.
Dashboard shows at most three active priorities, with all current-set details and
feedback available in a disclosure section.

## Versioned rules

`financial-recommendations-v1` uses demonstration policies, not prescriptions:

| Rule | Available factor condition | Priority |
| --- | --- | --- |
| SAVINGS_MARGIN | savings rate < 0.10 | HIGH at <= 0; otherwise MEDIUM |
| INCOME_VARIATION | normalized income variation >= 0.15 | MEDIUM |
| BUDGET_OVERRUN | actual/planned expense ratio > 1 | HIGH at >= 1.20; otherwise MEDIUM |
| GOAL_PACE | time-relative goal progress < 100 | MEDIUM below 50; otherwise LOW |

Unsupported/missing factors are skipped. INSUFFICIENT_DATA means no supporting
score is available; NO_ACTION_NEEDED means no rule matched, not guaranteed health.
Changed inputs create a new score and supersede only active old recommendations;
accepted/rejected/hidden feedback remains saved.

V22 permits justifications backed by available immutable score dimensions while
preserving indicator references, same-evaluation checks and justified activation.
Existing snapshots are not rewritten. Score publication and recommendation
generation serialize per owner to prevent duplicates during parallel refreshes.
Generation and feedback are audited without financial values. No AI is required.
