from __future__ import annotations

from collections.abc import Callable

import numpy as np
import pandas as pd
import pytest

from forecast_ml.feature_matrix import (
    FEATURE_COLUMNS,
    FEATURE_CONTRACT_VERSION,
    FORBIDDEN_FEATURE_COLUMNS,
    TARGET_COLUMN,
    build_experiment_matrix,
)


def test_feature_contract_excludes_outcomes_dates_and_identities(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    matrix = build_experiment_matrix(processed_frame_factory(), currency="TND")

    assert tuple(matrix.features.columns) == FEATURE_COLUMNS
    assert FEATURE_CONTRACT_VERSION == "forecast-residual-features-v1"
    assert not FORBIDDEN_FEATURE_COLUMNS.intersection(matrix.features.columns)
    assert TARGET_COLUMN not in matrix.features
    assert {"profile_key", "currency", "target_month", "cutoff_date"} <= set(
        matrix.context.columns
    )


def test_cyclical_month_features_are_deterministic(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    matrix = build_experiment_matrix(processed_frame_factory(profiles=("profile_0001",)))
    january = matrix.features.loc[matrix.context["target_month"] == pd.Period("2024-01")]
    july = matrix.features.loc[matrix.context["target_month"] == pd.Period("2024-07")]

    assert january["calendar_month_sin"].nunique() == 1
    assert january["calendar_month_cos"].nunique() == 1
    assert january.iloc[0]["calendar_month_sin"] == pytest.approx(0.0, abs=1e-12)
    assert january.iloc[0]["calendar_month_cos"] == pytest.approx(1.0)
    assert july.iloc[0]["calendar_month_sin"] == pytest.approx(0.0, abs=1e-12)
    assert july.iloc[0]["calendar_month_cos"] == pytest.approx(-1.0)


def test_multiple_currencies_require_an_explicit_selection(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    processed = processed_frame_factory(currencies=("TND", "EUR"))

    with pytest.raises(ValueError, match="choose exactly one currency"):
        build_experiment_matrix(processed)

    selected = build_experiment_matrix(processed, currency="EUR")
    assert selected.currency == "EUR"
    assert set(selected.context["currency"]) == {"EUR"}


def test_decimal_values_cross_to_floats_only_in_experiment_matrix(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    processed = processed_frame_factory(profiles=("profile_0001",))
    matrix = build_experiment_matrix(processed)

    assert processed["month_to_date_credit"].dtype == object
    assert np.issubdtype(matrix.features["month_to_date_credit"].dtype, np.floating)
    assert np.issubdtype(matrix.target.dtype, np.floating)


def test_processed_scale_and_residual_identity_are_validated(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    over_scale = processed_frame_factory(profiles=("profile_0001",))
    over_scale.loc[0, "month_to_date_credit"] = "1.00001"
    with pytest.raises(ValueError, match="scale-4"):
        build_experiment_matrix(over_scale)

    inconsistent = processed_frame_factory(profiles=("profile_0001",))
    inconsistent.loc[0, "residual_target"] = "999.0000"
    with pytest.raises(ValueError, match="does not match"):
        build_experiment_matrix(inconsistent)
