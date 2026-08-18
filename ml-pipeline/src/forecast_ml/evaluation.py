"""Reusable residual and remaining-flow evaluation without model selection."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Sequence

import numpy as np
import pandas as pd
from sklearn.metrics import (
    mean_absolute_error,
    median_absolute_error,
    root_mean_squared_error,
)

from forecast_ml.feature_matrix import ExperimentMatrix


@dataclass(frozen=True, slots=True)
class RegressionMetrics:
    row_count: int
    mae: float
    rmse: float
    median_absolute_error: float
    signed_mean_error: float
    improvement_percentage: float | None

    def as_record(self) -> dict[str, int | float | None]:
        return asdict(self)


def hybrid_remaining_flow(
    deterministic_remaining_net_flow: Sequence[float],
    predicted_residual: Sequence[float],
) -> np.ndarray:
    deterministic = _as_vector(deterministic_remaining_net_flow, "deterministic flow")
    residual = _as_vector(predicted_residual, "predicted residual")
    if len(deterministic) != len(residual):
        raise ValueError("deterministic flow and residual prediction lengths differ")
    return deterministic + residual


def residual_zero_predictions(row_count: int) -> np.ndarray:
    if row_count < 0:
        raise ValueError("row_count cannot be negative")
    return np.zeros(row_count, dtype=float)


def naive_remaining_flow_predictions(row_count: int) -> np.ndarray:
    if row_count < 0:
        raise ValueError("row_count cannot be negative")
    return np.zeros(row_count, dtype=float)


def calculate_regression_metrics(
    actual: Sequence[float],
    predicted: Sequence[float],
    *,
    deterministic_reference: Sequence[float],
) -> RegressionMetrics:
    """Calculate errors; signed mean error is prediction minus actual."""
    actual_values = _as_vector(actual, "actual values")
    predicted_values = _as_vector(predicted, "predicted values")
    reference_values = _as_vector(deterministic_reference, "deterministic reference")
    if not (len(actual_values) == len(predicted_values) == len(reference_values)):
        raise ValueError("actual, predicted, and reference lengths must match")
    if len(actual_values) == 0:
        raise ValueError("metrics require at least one row")

    mae = float(mean_absolute_error(actual_values, predicted_values))
    reference_mae = float(mean_absolute_error(actual_values, reference_values))
    if reference_mae == 0.0:
        improvement = None
    else:
        improvement = 100.0 * (reference_mae - mae) / reference_mae
    return RegressionMetrics(
        row_count=len(actual_values),
        mae=mae,
        rmse=float(root_mean_squared_error(actual_values, predicted_values)),
        median_absolute_error=float(median_absolute_error(actual_values, predicted_values)),
        signed_mean_error=float(np.mean(predicted_values - actual_values)),
        improvement_percentage=improvement,
    )


def prediction_frame(
    matrix: ExperimentMatrix,
    predicted_residual: Sequence[float],
) -> pd.DataFrame:
    residual = _as_vector(predicted_residual, "predicted residual")
    if len(residual) != len(matrix.features):
        raise ValueError("prediction count does not match experiment rows")
    deterministic = matrix.context["deterministic_remaining_net_flow"].to_numpy(dtype=float)
    actual = matrix.context["actual_remaining_net_flow"].to_numpy(dtype=float)
    hybrid = hybrid_remaining_flow(deterministic, residual)
    frame = matrix.context.copy()
    frame["predicted_residual"] = residual
    frame["predicted_remaining_net_flow"] = hybrid
    frame["prediction_error"] = hybrid - actual
    return frame


def evaluation_table(
    matrix: ExperimentMatrix,
    predicted_residual: Sequence[float],
    *,
    group_by: str | Sequence[str] | None = None,
) -> pd.DataFrame:
    frame = prediction_frame(matrix, predicted_residual)
    group_columns = _normalize_group_columns(group_by)
    if not group_columns:
        metrics = _metrics_for_frame(frame)
        return pd.DataFrame([metrics.as_record()])
    missing = sorted(set(group_columns) - set(frame.columns))
    if missing:
        raise ValueError("unknown evaluation grouping columns: " + ", ".join(missing))

    records: list[dict[str, object]] = []
    grouper: str | list[str] = group_columns[0] if len(group_columns) == 1 else group_columns
    for group_key, group in frame.groupby(grouper, sort=True, observed=True):
        keys = group_key if isinstance(group_key, tuple) else (group_key,)
        record = dict(zip(group_columns, keys))
        record.update(_metrics_for_frame(group).as_record())
        records.append(record)
    return pd.DataFrame(records)


def reference_comparison(matrix: ExperimentMatrix) -> pd.DataFrame:
    """Compare deterministic residual-zero and naive zero-remaining references."""
    actual = matrix.context["actual_remaining_net_flow"].to_numpy(dtype=float)
    deterministic = matrix.context["deterministic_remaining_net_flow"].to_numpy(dtype=float)
    rows = []
    for name, predicted in (
        ("residual_zero_deterministic", deterministic),
        ("naive_zero_remaining_flow", naive_remaining_flow_predictions(len(actual))),
    ):
        record: dict[str, object] = {"reference": name}
        record.update(
            calculate_regression_metrics(
                actual,
                predicted,
                deterministic_reference=deterministic,
            ).as_record()
        )
        rows.append(record)
    return pd.DataFrame(rows)


def _metrics_for_frame(frame: pd.DataFrame) -> RegressionMetrics:
    return calculate_regression_metrics(
        frame["actual_remaining_net_flow"].to_numpy(dtype=float),
        frame["predicted_remaining_net_flow"].to_numpy(dtype=float),
        deterministic_reference=frame["deterministic_remaining_net_flow"].to_numpy(dtype=float),
    )


def _as_vector(values: Sequence[float], name: str) -> np.ndarray:
    array = np.asarray(values, dtype=float)
    if array.ndim != 1:
        raise ValueError(f"{name} must be one-dimensional")
    if not np.isfinite(array).all():
        raise ValueError(f"{name} contains non-finite values")
    return array


def _normalize_group_columns(group_by: str | Sequence[str] | None) -> list[str]:
    if group_by is None:
        return []
    if isinstance(group_by, str):
        return [group_by]
    columns = list(group_by)
    if not columns:
        raise ValueError("group_by cannot be empty")
    return columns
