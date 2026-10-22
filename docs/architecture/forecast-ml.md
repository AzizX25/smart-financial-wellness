# Forecast ML prototype

## Status and limitation

The Forecast correction is an experimental prototype trained entirely on synthetic TND data. It is not guaranteed and is not presented with a confidence percentage because no probability or confidence calibration was performed. The deterministic forecast remains usable whenever inference is unavailable or ineligible.

## Frozen experiment

The selected estimator is a scikit-learn `HuberRegressor` with the frozen configuration:

```text
epsilon=1.35
alpha=0.0001
max_iter=1000
tol=1e-5
feature contract=forecast-residual-features-v1
currency=TND
```

Temporal split:

- Training: 2024-01 through 2025-06.
- Validation: 2025-07 through 2025-09.
- Untouched test: 2025-10 through 2025-12.
- Final artifact fit: training plus validation only; no test row was fitted.

Frozen test metrics:

| Metric | Value |
| --- | ---: |
| Deterministic MAE | 344.948136 |
| Huber MAE | 328.065829 |
| Improvement | 4.894158% |
| Huber RMSE | 467.627051 |
| Huber median AE | 186.662423 |
| Huber signed error | -168.558884 |

Signed error is `prediction - observed`; a negative value means under-prediction. Test improvement by cutoff was +3.475886% (day 5), +5.824223% (day 10), +7.283011% (day 15), +11.480422% (day 20), and -19.620741% (day 25). This day-25 instability is disclosed but is not used as a gate: changing eligibility requires new real data and a new untouched test period.

## Provenance and artifact

- Generator seed: `20260816`.
- Generator: five profiles, TND, 2024-01 through 2025-12, cutoff days 5/10/15/20/25, as-of date 2026-01-01.
- Dataset version: `forecast-residual-v1`.
- Raw dataset SHA-256: `943a56e8f9ce8de7059fef336c818f23dae7f6845655df5aa437556bfd1b8139`.
- Processed dataset SHA-256: `763e102ae5b7bc71a6add3603fd5d7a99b231969c9bec2fb17e7451065cf2dbc`.
- Artifact: `ai-service/models/forecast/forecast_huber_v1.joblib`.
- Artifact SHA-256: `fa286c88243521755e154961ea8668efbfe3d57312efb50afc47b34f38e217c6`.
- Metadata: `ai-service/models/forecast/forecast_huber_v1.metadata.json`.

The authoritative exporter is `ml-pipeline/scripts/export_frozen_model.py`; notebook 03 only explains and invokes it. Generic pipeline artifacts and raw/processed CSV files remain ignored. The trusted repository artifact hash is verified before deserialization.

## Feature and service boundary

The backend assembles aggregate, leakage-safe inputs for `forecast-residual-features-v1`: calendar month, cutoff/day remainder, month-to-date credit/debit/net/count, trailing-six-month credit/debit/net, historical remaining-flow range, deterministic remaining income/expense/net, usable-history count, and deterministic fallback reason. The service derives month sine/cosine and constructs the exact training column order.

The internal FastAPI service exposes:

- `GET /health`
- `GET /api/v1/forecast/model`
- `POST /api/v1/forecast/predict-residual`

Requests are strict, TND-only, scale-4 decimal contracts. Responses contain the scale-4 residual plus the exact model/feature identifiers and `experimental=true`, `syntheticTrainingData=true`, `guaranteed=false`. The backend validates all of those fields and falls back deterministically on timeouts, connectivity errors, invalid values, incompatible versions, insufficient history, or unsupported currency.

## Runtime configuration

Compose builds the artifact into `ai-service`, verifies it at startup, and keeps port 8000 internal. The backend uses `AI_SERVICE_BASE_URL`, connect/read timeouts, and expected model/feature identifiers. Backend startup does not depend on AI health, so stopping the inference container must not stop the API.

## Future replacement protocol

A future real-data model must be treated as a new experiment: version the dataset and feature contract, prevent temporal leakage, reserve a new untouched test period, record seeds/configuration/dependencies, validate the candidate before promotion, export a new immutable artifact and metadata pair, update expected identifiers, and retain deterministic fallback. The current frozen Huber artifact must not be silently overwritten or retuned.
