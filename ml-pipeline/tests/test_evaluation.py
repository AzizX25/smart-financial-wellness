from __future__ import annotations

from collections.abc import Callable
from math import sqrt

import numpy as np
import pandas as pd
import pytest

from forecast_ml.evaluation import (
    calculate_regression_metrics,
    evaluation_table,
    hybrid_remaining_flow,
    naive_remaining_flow_predictions,
    prediction_frame,
    reference_comparison,
    residual_zero_predictions,
)
from forecast_ml.feature_matrix import build_experiment_matrix


def test_all_required_regression_metrics_and_improvement_are_correct() -> None:
    metrics = calculate_regression_metrics(
        [1.0, 3.0],
        [2.0, 1.0],
        deterministic_reference=[0.0, 0.0],
    )

    assert metrics.row_count == 2
    assert metrics.mae == pytest.approx(1.5)
    assert metrics.rmse == pytest.approx(sqrt(2.5))
    assert metrics.median_absolute_error == pytest.approx(1.5)
    assert metrics.signed_mean_error == pytest.approx(-0.5)
    assert metrics.improvement_percentage == pytest.approx(25.0)


def test_zero_reference_mae_produces_undefined_safe_improvement() -> None:
    metrics = calculate_regression_metrics(
        [0.0],
        [0.0],
        deterministic_reference=[0.0],
    )

    assert metrics.improvement_percentage is None


def test_hybrid_and_reference_prediction_arithmetic() -> None:
    deterministic = np.array([10.0, -5.0])
    residual = np.array([2.5, -1.0])

    assert np.array_equal(hybrid_remaining_flow(deterministic, residual), [12.5, -6.0])
    assert np.array_equal(residual_zero_predictions(2), [0.0, 0.0])
    assert np.array_equal(naive_remaining_flow_predictions(2), [0.0, 0.0])


def test_naive_zero_remaining_flow_metrics_are_exact() -> None:
    actual = np.array([2.0, -4.0])
    naive = naive_remaining_flow_predictions(len(actual))

    metrics = calculate_regression_metrics(
        actual,
        naive,
        deterministic_reference=[1.0, -2.0],
    )

    assert metrics.mae == pytest.approx(3.0)
    assert metrics.rmse == pytest.approx(sqrt(10.0))
    assert metrics.median_absolute_error == pytest.approx(3.0)
    assert metrics.signed_mean_error == pytest.approx(1.0)


def test_prediction_frame_applies_residual_to_deterministic_forecast(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    matrix = build_experiment_matrix(
        processed_frame_factory(profiles=("profile_0001",), cutoffs=(5,))
    ).take([0, 1])
    predicted_residual = np.array([3.0, -4.0])

    predictions = prediction_frame(matrix, predicted_residual)

    expected = (
        matrix.context["deterministic_remaining_net_flow"].to_numpy() + predicted_residual
    )
    assert np.array_equal(predictions["predicted_remaining_net_flow"], expected)
    assert np.array_equal(
        predictions["prediction_error"],
        expected - matrix.context["actual_remaining_net_flow"].to_numpy(),
    )


def test_residual_zero_and_naive_remaining_references_remain_distinct(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    matrix = build_experiment_matrix(
        processed_frame_factory(profiles=("profile_0001",), cutoffs=(5,))
    ).take([0, 1, 2])

    comparison = reference_comparison(matrix).set_index("reference")

    deterministic = comparison.loc["residual_zero_deterministic"]
    naive = comparison.loc["naive_zero_remaining_flow"]
    assert deterministic["improvement_percentage"] == pytest.approx(0.0)
    assert deterministic["mae"] != naive["mae"]


def test_per_cutoff_evaluation_uses_group_specific_reference(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    matrix = build_experiment_matrix(
        processed_frame_factory(profiles=("profile_0001",))
    )
    predictions = np.zeros(len(matrix.features))

    by_cutoff = evaluation_table(matrix, predictions, group_by="cutoff_day")

    assert set(by_cutoff["cutoff_day"]) == {5, 25}
    assert set(by_cutoff["row_count"]) == {24}
    assert np.allclose(by_cutoff["improvement_percentage"], 0.0)


def test_supported_diagnostic_groupings_return_every_group(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    matrix = build_experiment_matrix(processed_frame_factory())
    predictions = np.zeros(len(matrix.features))

    by_history = evaluation_table(matrix, predictions, group_by="usable_history_count")
    by_fallback = evaluation_table(
        matrix, predictions, group_by="deterministic_fallback_reason"
    )
    by_profile = evaluation_table(matrix, predictions, group_by="profile_key")

    assert set(by_history["usable_history_count"]) == set(range(7))
    assert set(by_fallback["deterministic_fallback_reason"]) == {
        "NONE",
        "LIMITED_HISTORY",
        "NO_USABLE_HISTORY",
    }
    assert set(by_profile["profile_key"]) == {"profile_0001", "profile_0002"}
