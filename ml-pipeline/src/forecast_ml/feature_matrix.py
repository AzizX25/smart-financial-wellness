"""Explicit, leakage-safe feature contract for residual-model experiments."""

from __future__ import annotations

import csv
import math
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Final

import numpy as np
import pandas as pd

from forecast_ml.schemas import PROCESSED_COLUMNS

TARGET_COLUMN: Final = "residual_target"
FEATURE_CONTRACT_VERSION: Final = "forecast-residual-features-v1"

NUMERIC_FEATURE_COLUMNS: Final[tuple[str, ...]] = (
    "calendar_month_sin",
    "calendar_month_cos",
    "cutoff_day",
    "days_remaining",
    "month_to_date_credit",
    "month_to_date_debit",
    "month_to_date_net_flow",
    "month_to_date_transaction_count",
    "trailing_six_month_credit",
    "trailing_six_month_debit",
    "trailing_six_month_net_flow",
    "historical_remaining_net_flow_range",
    "deterministic_remaining_income",
    "deterministic_remaining_expense",
    "deterministic_remaining_net_flow",
    "usable_history_count",
)
CATEGORICAL_FEATURE_COLUMNS: Final[tuple[str, ...]] = (
    "deterministic_fallback_reason",
)
FEATURE_COLUMNS: Final[tuple[str, ...]] = (
    *NUMERIC_FEATURE_COLUMNS,
    *CATEGORICAL_FEATURE_COLUMNS,
)

FORBIDDEN_FEATURE_COLUMNS: Final[frozenset[str]] = frozenset(
    {
        "residual_target",
        "actual_remaining_income",
        "actual_remaining_expense",
        "actual_remaining_net_flow",
        "target_month",
        "cutoff_date",
        "dataset_version",
        "profile_key",
        "currency",
    }
)

CONTEXT_COLUMNS: Final[tuple[str, ...]] = (
    "profile_key",
    "currency",
    "target_month",
    "cutoff_date",
    "cutoff_day",
    "usable_history_count",
    "deterministic_fallback_reason",
    "deterministic_remaining_net_flow",
    "actual_remaining_net_flow",
)

_MONEY_COLUMNS = (
    "month_to_date_credit",
    "month_to_date_debit",
    "month_to_date_net_flow",
    "trailing_six_month_credit",
    "trailing_six_month_debit",
    "trailing_six_month_net_flow",
    "historical_remaining_net_flow_range",
    "deterministic_remaining_income",
    "deterministic_remaining_expense",
    "deterministic_remaining_net_flow",
    "actual_remaining_income",
    "actual_remaining_expense",
    "actual_remaining_net_flow",
    "residual_target",
)
_FALLBACK_REASONS = frozenset({"NONE", "LIMITED_HISTORY", "NO_USABLE_HISTORY"})
_MONTH_PATTERN = re.compile(r"^[0-9]{4}-(?:0[1-9]|1[0-2])$")
_DATE_PATTERN = re.compile(r"^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])$")
_DECIMAL_PATTERN = re.compile(r"^-?(?:0|[1-9][0-9]{0,14})(?:\.[0-9]{1,4})?$")


@dataclass(frozen=True, slots=True)
class ExperimentMatrix:
    """Model inputs, residual target, and non-feature evaluation context."""

    features: pd.DataFrame
    target: pd.Series
    context: pd.DataFrame
    currency: str

    def __post_init__(self) -> None:
        row_count = len(self.features)
        if row_count != len(self.target) or row_count != len(self.context):
            raise ValueError("features, target, and context must have equal row counts")
        if tuple(self.features.columns) != FEATURE_COLUMNS:
            raise ValueError("feature matrix does not match the centralized feature contract")
        if FORBIDDEN_FEATURE_COLUMNS.intersection(self.features.columns):
            raise ValueError("forbidden outcome or identity column entered the feature matrix")

    def take(self, positions: list[int] | np.ndarray) -> "ExperimentMatrix":
        return ExperimentMatrix(
            features=self.features.iloc[positions].reset_index(drop=True),
            target=self.target.iloc[positions].reset_index(drop=True),
            context=self.context.iloc[positions].reset_index(drop=True),
            currency=self.currency,
        )


def load_processed_dataset(path: str | Path) -> pd.DataFrame:
    """Load the stable processed CSV without implicit numeric conversion."""
    input_path = Path(path)
    with input_path.open("r", encoding="utf-8", newline="") as handle:
        header = next(csv.reader(handle), None)
    if header is None:
        raise ValueError("processed dataset must contain a header")
    if tuple(header) != PROCESSED_COLUMNS or len(header) != len(set(header)):
        raise ValueError("processed dataset columns must exactly match the stable schema")
    return pd.read_csv(input_path, dtype=str, keep_default_na=False)


def build_experiment_matrix(
    processed: pd.DataFrame,
    *,
    currency: str | None = None,
) -> ExperimentMatrix:
    """Validate processed rows and cross the explicit Decimal-to-float boundary."""
    _require_processed_columns(processed)
    selected_currency = _select_currency(processed, currency)
    scoped = processed.loc[processed["currency"] == selected_currency].reset_index(drop=True)
    if scoped.empty:
        raise ValueError(f"no processed rows exist for currency {selected_currency}")

    target_month = _parse_months(scoped["target_month"])
    cutoff_date = _parse_dates(scoped["cutoff_date"])
    calendar_month = _parse_integer(scoped["calendar_month"], "calendar_month", 1, 12)
    cutoff_day = _parse_integer(scoped["cutoff_day"], "cutoff_day", 1, 31)
    days_remaining = _parse_integer(scoped["days_remaining"], "days_remaining", 0, 31)
    transaction_count = _parse_integer(
        scoped["month_to_date_transaction_count"],
        "month_to_date_transaction_count",
        0,
        None,
    )
    usable_history_count = _parse_integer(
        scoped["usable_history_count"], "usable_history_count", 0, 6
    )
    fallbacks = scoped["deterministic_fallback_reason"].astype(str)
    invalid_fallbacks = sorted(set(fallbacks) - _FALLBACK_REASONS)
    if invalid_fallbacks:
        raise ValueError(
            "unknown deterministic_fallback_reason values: " + ", ".join(invalid_fallbacks)
        )

    if not np.array_equal(calendar_month.to_numpy(), target_month.month.to_numpy()):
        raise ValueError("calendar_month must match target_month")
    if not np.array_equal(cutoff_day.to_numpy(), cutoff_date.dt.day.to_numpy()):
        raise ValueError("cutoff_day must match cutoff_date")
    cutoff_periods = cutoff_date.dt.to_period("M")
    if not np.array_equal(target_month.astype(str).to_numpy(), cutoff_periods.astype(str).to_numpy()):
        raise ValueError("cutoff_date must fall within target_month")

    decimal_values = {
        column: _parse_decimal(scoped[column], column)
        for column in _MONEY_COLUMNS
    }
    for row_number, (actual, deterministic, residual) in enumerate(
        zip(
            decimal_values["actual_remaining_net_flow"],
            decimal_values["deterministic_remaining_net_flow"],
            decimal_values["residual_target"],
        ),
        start=2,
    ):
        if actual - deterministic != residual:
            raise ValueError(f"row {row_number}: residual_target does not match its definition")

    radians = 2.0 * math.pi * (calendar_month.to_numpy(dtype=float) - 1.0) / 12.0
    features = pd.DataFrame(
        {
            "calendar_month_sin": np.sin(radians),
            "calendar_month_cos": np.cos(radians),
            "cutoff_day": cutoff_day.to_numpy(dtype=float),
            "days_remaining": days_remaining.to_numpy(dtype=float),
            "month_to_date_credit": _to_float(decimal_values["month_to_date_credit"]),
            "month_to_date_debit": _to_float(decimal_values["month_to_date_debit"]),
            "month_to_date_net_flow": _to_float(decimal_values["month_to_date_net_flow"]),
            "month_to_date_transaction_count": transaction_count.to_numpy(dtype=float),
            "trailing_six_month_credit": _to_float(
                decimal_values["trailing_six_month_credit"]
            ),
            "trailing_six_month_debit": _to_float(
                decimal_values["trailing_six_month_debit"]
            ),
            "trailing_six_month_net_flow": _to_float(
                decimal_values["trailing_six_month_net_flow"]
            ),
            "historical_remaining_net_flow_range": _to_float(
                decimal_values["historical_remaining_net_flow_range"]
            ),
            "deterministic_remaining_income": _to_float(
                decimal_values["deterministic_remaining_income"]
            ),
            "deterministic_remaining_expense": _to_float(
                decimal_values["deterministic_remaining_expense"]
            ),
            "deterministic_remaining_net_flow": _to_float(
                decimal_values["deterministic_remaining_net_flow"]
            ),
            "usable_history_count": usable_history_count.to_numpy(dtype=float),
            "deterministic_fallback_reason": fallbacks.to_numpy(),
        },
        columns=FEATURE_COLUMNS,
    )
    target = pd.Series(
        _to_float(decimal_values["residual_target"]),
        name=TARGET_COLUMN,
    )
    context = pd.DataFrame(
        {
            "profile_key": scoped["profile_key"].astype(str),
            "currency": scoped["currency"].astype(str),
            "target_month": target_month,
            "cutoff_date": cutoff_date,
            "cutoff_day": cutoff_day,
            "usable_history_count": usable_history_count,
            "deterministic_fallback_reason": fallbacks,
            "deterministic_remaining_net_flow": _to_float(
                decimal_values["deterministic_remaining_net_flow"]
            ),
            "actual_remaining_net_flow": _to_float(
                decimal_values["actual_remaining_net_flow"]
            ),
        },
        columns=CONTEXT_COLUMNS,
    )
    return ExperimentMatrix(features, target, context, selected_currency)


def combine_experiment_matrices(*matrices: ExperimentMatrix) -> ExperimentMatrix:
    if not matrices:
        raise ValueError("at least one experiment matrix is required")
    currencies = {matrix.currency for matrix in matrices}
    if len(currencies) != 1:
        raise ValueError("experiment matrices from different currencies cannot be combined")
    return ExperimentMatrix(
        features=pd.concat([matrix.features for matrix in matrices], ignore_index=True),
        target=pd.concat([matrix.target for matrix in matrices], ignore_index=True),
        context=pd.concat([matrix.context for matrix in matrices], ignore_index=True),
        currency=matrices[0].currency,
    )


def _require_processed_columns(processed: pd.DataFrame) -> None:
    columns = tuple(str(column) for column in processed.columns)
    if columns != PROCESSED_COLUMNS or len(columns) != len(set(columns)):
        raise ValueError("processed data must exactly match the stable processed schema")
    if processed.empty:
        raise ValueError("processed data must contain at least one row")
    if processed.isna().any().any():
        raise ValueError("processed data cannot contain missing values")


def _select_currency(processed: pd.DataFrame, requested: str | None) -> str:
    currencies = sorted(set(processed["currency"].astype(str)))
    if requested is None:
        if len(currencies) != 1:
            raise ValueError(
                "processed dataset contains multiple currencies; choose exactly one currency"
            )
        return currencies[0]
    if requested not in currencies:
        raise ValueError(
            f"currency {requested} is absent; available currencies: {', '.join(currencies)}"
        )
    return requested


def _parse_months(values: pd.Series) -> pd.PeriodIndex:
    raw = values.astype(str)
    if not raw.map(lambda value: bool(_MONTH_PATTERN.fullmatch(value))).all():
        raise ValueError("target_month must use exact YYYY-MM values")
    return pd.PeriodIndex(raw, freq="M")


def _parse_dates(values: pd.Series) -> pd.Series:
    raw = values.astype(str)
    if not raw.map(lambda value: bool(_DATE_PATTERN.fullmatch(value))).all():
        raise ValueError("cutoff_date must use exact YYYY-MM-DD values")
    try:
        parsed = pd.to_datetime(raw, format="%Y-%m-%d", errors="raise")
    except (TypeError, ValueError) as exception:
        raise ValueError("cutoff_date contains an invalid calendar date") from exception
    return pd.Series(parsed, index=values.index, name="cutoff_date")


def _parse_integer(
    values: pd.Series,
    column: str,
    minimum: int,
    maximum: int | None,
) -> pd.Series:
    raw = values.astype(str)
    if not raw.str.fullmatch(r"[0-9]+").all():
        raise ValueError(f"{column} must contain integers")
    parsed = raw.astype(int)
    if (parsed < minimum).any() or (maximum is not None and (parsed > maximum).any()):
        raise ValueError(f"{column} is outside its allowed range")
    return parsed


def _parse_decimal(values: pd.Series, column: str) -> tuple[Decimal, ...]:
    parsed: list[Decimal] = []
    for row_number, value in enumerate(values.astype(str), start=2):
        if not _DECIMAL_PATTERN.fullmatch(value):
            raise ValueError(f"row {row_number}: {column} must be a scale-4 decimal")
        try:
            decimal_value = Decimal(value)
        except InvalidOperation as exception:
            raise ValueError(f"row {row_number}: {column} is not a decimal") from exception
        if not decimal_value.is_finite() or decimal_value.as_tuple().exponent < -4:
            raise ValueError(f"row {row_number}: {column} exceeds scale 4")
        parsed.append(decimal_value)
    return tuple(parsed)


def _to_float(values: tuple[Decimal, ...]) -> np.ndarray:
    return np.fromiter((float(value) for value in values), dtype=float, count=len(values))
