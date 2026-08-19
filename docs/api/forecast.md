# Forecast API

The Forecast API provides authenticated, owner-scoped current-month forecasts. It never creates financial accounts, snapshots, transactions, Salary Plans, or fallback financial data.

## Endpoints

| Method | Path | Responsibility |
| --- | --- | --- |
| `POST` | `/api/forecasts/current?currency=TND&timezone=Africa%2FTunis` | Generate or return the idempotent forecast for the current eligible input. |
| `GET` | `/api/forecasts?currency=TND&page=0&size=12` | List the current user's saved forecasts for one exact currency. |
| `GET` | `/api/forecasts/{forecastId}` | Return one owned immutable forecast; a foreign ID is reported as not found. |
| `POST` | `/api/forecasts/{forecastId}/simulations` | Calculate a temporary adjustment without persistence or an ML call. |

Authentication uses the normal bearer access token. Ownership always comes from the authenticated principal; no endpoint accepts a user or account identifier.

`POST /current` returns HTTP 200 with `available: false` when accounts, snapshots, a common cutoff, or a current-month cutoff are unavailable. When a result is available, the response contains both the deterministic result and the applied/fallback ML status:

- `APPLIED`
- `UNSUPPORTED_CURRENCY`
- `INSUFFICIENT_HISTORY`
- `SERVICE_UNAVAILABLE`
- `INVALID_RESPONSE`
- `MODEL_VERSION_MISMATCH`

AI failure is not an API failure: the final balance falls back to the deterministic balance. A model-applied response always carries `experimental: true`, `syntheticTrainingData: true`, and `guaranteed: false`.

## Calculations

```text
deterministicPredictedEndBalance =
    startingBalance + deterministicRemainingNetFlow

finalPredictedEndBalance =
    deterministicPredictedEndBalance + mlPredictedResidual
```

When ML is not applied, `finalPredictedEndBalance` equals the deterministic result. Amounts are decimal values at scale 4 and currencies are never combined or converted.

Simulation accepts optional non-negative magnitudes (default zero):

```json
{
  "additionalIncome": "100.0000",
  "additionalExpense": "25.5000"
}
```

The server calculates:

```text
simulatedEndBalance = persistedFinalPredictedEndBalance
                      + additionalIncome
                      - additionalExpense
```

The simulation persists nothing, calls no model, and is always marked `guaranteed: false`.

## Internal AI contract

The backend calls `POST /api/v1/forecast/predict-residual` on the internal AI service. The request contains only aggregated temporal and monetary features—never user IDs, account identifiers, transaction rows, categories, descriptions, or merchant data. Model ID/version, feature version, currency, decimal scale, and the experimental flags are validated before a residual is accepted.
