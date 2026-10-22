# Forecast ML data workspace

This isolated workspace prepares and audits pseudonymized, leakage-safe examples for a future Forecast residual model. It reproduces the backend deterministic remaining-period baseline and builds the target below, but it does **not** train, select, evaluate, serve, or integrate a model.

```text
actual_remaining_net_flow = post-cutoff credits - post-cutoff debits
residual_target = actual_remaining_net_flow - deterministic_remaining_net_flow
```

The future hybrid calculation is expected to add a separately trained residual prediction to the starting balance and deterministic baseline. Starting balances and balance snapshots are intentionally absent here because this dataset predicts remaining cash flow, not an end balance directly.

## Windows PowerShell setup

Python 3.12 is the documented local version; the manifest also supports Python 3.11 and 3.13.

```powershell
Set-Location .\ml-pipeline
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -e ".[test]"
```

Generate the deterministic demonstration data and build completed-month examples:

```powershell
python .\scripts\generate_synthetic_data.py `
  --output .\data\raw\synthetic_transactions.csv `
  --seed 20260816 `
  --profiles 5 `
  --start-month 2024-01 `
  --end-month 2025-12 `
  --currency TND

python .\scripts\build_training_dataset.py `
  --input .\data\raw\synthetic_transactions.csv `
  --output .\data\processed\forecast_residual_examples.csv `
  --as-of 2026-01-01 `
  --cutoffs 5,10,15,20,25
```

Launch or execute the audit notebook:

```powershell
$env:FORECAST_RAW_DATA = ".\data\raw\synthetic_transactions.csv"
$env:FORECAST_AS_OF_DATE = "2026-01-01"
jupyter lab .\notebooks\01_data_audit.ipynb

jupyter nbconvert --to notebook --execute .\notebooks\01_data_audit.ipynb `
  --output 01_data_audit-executed.ipynb `
  --output-dir .\notebooks\.executed `
  --ExecutePreprocessor.timeout=180
```

Run all tests:

```powershell
python -m pytest
```

All generated raw/processed data, executed notebooks, notebook checkpoints, virtual environments, and artifacts are ignored by the local `.gitignore`.

## Raw CSV contract

The header and its order are strict:

| Column | Format and meaning |
|---|---|
| `profile_key` | Stable pseudonym inside this local dataset; starts with an ASCII letter and uses at most 64 letters, digits, `_`, or `-`; UUIDs are rejected. |
| `account_key` | Stable account pseudonym with the same restrictions. |
| `booking_date` | Exact ISO date (`YYYY-MM-DD`) of the cash flow. |
| `known_date` | First local calendar date on which the application knew the row; never before `booking_date`. |
| `amount` | Strictly positive decimal magnitude with at most four fractional digits. |
| `direction` | `DEBIT` or `CREDIT`; it supplies the sign. |
| `currency` | Three uppercase ASCII letters. Each currency is processed independently. |
| `source` | `MANUAL`, `FILE_IMPORT`, or `BANK_SYNC`. |
| `provider_status` | Required for `BANK_SYNC`; blank for manual/file rows. |
| `is_internal_transfer` | Lowercase `true` or `false`; true only for a confirmed internal transfer. |
| `category_code` | Optional non-personal category code; it never controls cash-flow eligibility. |

The validator rejects missing, reordered, duplicate, or unexpected columns. It explicitly flags sensitive-looking unexpected columns and never accepts names, email addresses, database user IDs, tokens, account numbers, descriptions, merchant/counterparty text, addresses, or phone numbers.

For an application export, derive `known_date` from when the row first became available to the application—for example, the user-local date of `created_at` for manual/file rows and `first_imported_at` for bank rows. Do not replace it with the booking date when late-arriving rows exist.

To audit your own file safely, place it under the ignored `data/raw/` directory, keep the exact contract, and run the builder or notebook:

```powershell
Copy-Item C:\path\to\pseudonymized-transactions.csv .\data\raw\my-transactions.csv
$env:FORECAST_RAW_DATA = ".\data\raw\my-transactions.csv"
$env:FORECAST_AS_OF_DATE = "2026-08-01"
jupyter lab .\notebooks\01_data_audit.ipynb
```

Before copying, remove all direct identifiers and free text. Keys must be stable only within the local export; do not use database IDs, hashes of emails/account numbers, or reversible identifiers. Never add the data file to Git.

## Processed CSV contract

`dataset_version` is currently `forecast-residual-v1`. The stable ordered columns are:

| Columns | Meaning |
|---|---|
| `dataset_version`, `profile_key`, `currency` | Contract version and isolated pseudonymous series. |
| `target_month`, `cutoff_date`, `calendar_month`, `cutoff_day`, `days_remaining` | Prediction calendar context. |
| `month_to_date_credit`, `month_to_date_debit`, `month_to_date_net_flow`, `month_to_date_transaction_count` | Eligible target-month rows booked and known no later than the cutoff. |
| `trailing_six_month_credit`, `trailing_six_month_debit`, `trailing_six_month_net_flow` | Eligible, known-at-cutoff cash flow from at most six prior completed months. |
| `historical_remaining_net_flow_range` | Maximum minus minimum remaining net flow across usable baseline months. |
| `deterministic_remaining_income`, `deterministic_remaining_expense`, `deterministic_remaining_net_flow` | Java-parity historical baseline. |
| `usable_history_count`, `deterministic_fallback_reason` | Explainability metadata (`NONE`, `LIMITED_HISTORY`, or `NO_USABLE_HISTORY`). |
| `actual_remaining_income`, `actual_remaining_expense`, `actual_remaining_net_flow` | Completed-month outcome strictly after the cutoff. |
| `residual_target` | Actual remaining net flow minus deterministic remaining net flow. |

Money is serialized with exactly four decimal places. Python calculations remain `Decimal`; any conversion to a floating-point training matrix belongs to a later, explicit training decision.

## Cutoffs, eligibility, and leakage controls

- An example exists only when its target calendar month ended before `--as-of`; rows not yet known on that date are ignored.
- Default target cutoffs are days 5, 10, 15, 20, and 25.
- Features see only eligible rows whose `booking_date` and `known_date` are on or before the target cutoff.
- Labels see eligible rows booked strictly after the cutoff through target month end.
- The deterministic baseline and trailing features see at most the six preceding completed months, filtered by the target cutoff's `known_date`.
- A historical month is usable when it contains at least one eligible row, even if it has no remaining flow after the analogous cutoff.
- The analogous cutoff is clamped to the historical month's final day. Income and expense are averaged separately with scale-4 `HALF_EVEN` division.
- Manual and file-imported rows are eligible without provider status. Bank rows are eligible only with `POSTED`. Pending, reversed, and unknown bank states are excluded.
- Confirmed internal transfers are excluded. Category code/state/nature does not affect base cash-flow eligibility.
- Profile and currency filters are applied independently; no conversion or cross-series aggregation occurs.
- Post-cutoff outcomes are deliberately absent from `feature_values()`.

The synthetic generator creates salaries, irregular income, fixed and discretionary costs, seasonal costs, occasional shocks, transfer pairs, and non-posted bank rows. It uses a local `random.Random(seed)` and stable ordering, so identical arguments produce identical CSV bytes.

## Backend parity and deliberate differences

The baseline matches `DeterministicForecastCalculator`: method `HISTORICAL_REMAINING_PERIOD_AVERAGE`, version `1`, six prior months, usable-month semantics, analogous-cutoff clamping, separate income/expense averages, scale-4 `HALF_EVEN`, and fallback rules. The neutral JSON fixture locks representative Java/Python results.

The production extractor runs against the database at request time, restricts active accounts, derives a common current-month cutoff from balance snapshots, and calculates a starting balance. This offline workspace assumes the raw export was already scoped to the intended accounts, reconstructs historical point-in-time visibility with `known_date`, creates backtesting rows for completed months, and omits snapshots/starting balances because the target is only the residual cash flow. Python also receives a single confirmed-transfer flag; the exporter must derive it using the backend's paired internal-transfer ownership rule.

## Decisions intentionally left open

After reviewing `01_data_audit.ipynb`, record your decisions in its TODO cells:

- which leakage-safe candidate columns become model inputs;
- how to split train/validation/test periods without temporal or profile leakage;
- how to evaluate fallback groups and currencies separately;
- which model families and metrics to compare;
- whether flagged outliers are valid events, data defects, or candidates for a documented treatment;
- how dataset configuration, evaluation results, and any future artifact will be versioned.

No current source file makes those choices, and no model artifact is produced.

## Baseline-model experiment workflow

`notebooks/02_baseline_models.ipynb` is the manual validation and model-selection workspace for the residual target. It compares four fixed candidates without hyperparameter search:

- residual-zero deterministic reference;
- Ridge with scaled numeric inputs;
- Huber regression with scaled numeric inputs;
- shallow, regularized histogram gradient boosting with a fixed seed.

The centralized feature contract is versioned as `forecast-residual-features-v1`. All learned preprocessing is inside scikit-learn pipelines and is fitted on training data only. Calendar month becomes explicit sine/cosine features. `deterministic_fallback_reason` is one-hot encoded with fixed categories. Identity columns, currency, raw target dates, dataset version, realized outcomes, and `residual_target` never enter the feature matrix.

The default chronological boundaries are fixed and are never inferred from the data:

```text
Training:   2024-01 through 2025-06
Validation: 2025-07 through 2025-09
Test:       2025-10 through 2025-12
```

Every cutoff from one profile-month stays in one partition. Validation may be inspected repeatedly during development. Test rows remain behind a one-use guard until a known candidate name is selected and `MODEL_SELECTION_FROZEN` is exactly `True`.

Use these PowerShell commands and steps:

```powershell
Set-Location .\ml-pipeline
.\.venv\Scripts\Activate.ps1

# Install the updated local dependencies, including scikit-learn.
python -m pip install -e ".[test]"

# Rebuild the processed dataset only when the raw input or builder configuration changed.
python .\scripts\build_training_dataset.py `
  --input .\data\raw\synthetic_transactions.csv `
  --output .\data\processed\forecast_residual_examples.csv `
  --as-of 2026-01-01 `
  --cutoffs 5,10,15,20,25

$env:FORECAST_PROCESSED_DATA = ".\data\processed\forecast_residual_examples.csv"
$env:FORECAST_EXPERIMENT_CURRENCY = "TND"
jupyter lab .\notebooks\02_baseline_models.ipynb
```

Inside the notebook:

1. Run sections 1–18 to inspect training and validation behavior only.
2. Complete every TODO in section 19, including the day-25 and synthetic-data limitations.
3. Record the selected candidate and the validation-only reason for choosing it.
4. Confirm in writing that the choice was made before test results were viewed.
5. Edit the configuration cell so `SELECTED_MODEL_NAME` exactly matches a known candidate.
6. Set `MODEL_SELECTION_FROZEN = True`.
7. Execute section 20 exactly once. It recreates the selected pipeline, fits training+validation, and opens the untouched test partition.
8. Do not revise the model choice using test results and do not export the fitted pipeline.

Run the unit tests and clear all notebook outputs before a future commit:

```powershell
python -m pytest

jupyter nbconvert `
  --ClearOutputPreprocessor.enabled=True `
  --inplace `
  .\notebooks\02_baseline_models.ipynb
```

Five cutoff observations from a profile-month are correlated; row count is not the number of independent monthly observations. Full-dataset EDA metrics—including any global deterministic improvement—are descriptive, not validation metrics. Synthetic-data results do not prove production accuracy, and real pseudonymized data must be evaluated later with a separately frozen protocol. This increment produces no trained artifact.
