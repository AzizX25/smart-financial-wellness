from __future__ import annotations

from collections.abc import Callable

import pandas as pd
import pytest

from forecast_ml.feature_matrix import build_experiment_matrix
from forecast_ml.modeling import CANDIDATE_MODEL_NAMES
from forecast_ml.temporal_split import (
    MonthRange,
    TemporalBoundaries,
    chronological_split,
)


def test_default_periods_are_chronological_non_overlapping_and_non_empty(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    split = chronological_split(build_experiment_matrix(processed_frame_factory()))
    summary = split.partition_summary().set_index("partition")

    assert summary.loc["training", "row_count"] == 72
    assert summary.loc["validation", "row_count"] == 12
    assert summary.loc["test_locked", "row_count"] == 12
    assert summary.loc["training", "profile_month_group_count"] == 36
    assert summary.loc["validation", "profile_month_group_count"] == 6
    assert summary.loc["test_locked", "profile_month_group_count"] == 6


def test_invalid_period_order_is_rejected() -> None:
    with pytest.raises(ValueError, match="start must not be after"):
        MonthRange("2025-09", "2025-07")
    with pytest.raises(ValueError, match="ordered and non-overlapping"):
        TemporalBoundaries(
            MonthRange("2024-01", "2025-07"),
            MonthRange("2025-07", "2025-09"),
            MonthRange("2025-10", "2025-12"),
        )


def test_empty_configured_partition_is_rejected(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    processed = processed_frame_factory()
    processed = processed.loc[processed["target_month"] <= "2025-09"].reset_index(drop=True)

    with pytest.raises(ValueError, match="test partition is empty"):
        chronological_split(build_experiment_matrix(processed))


def test_profile_month_cutoffs_stay_together_and_row_order_is_irrelevant(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    processed = processed_frame_factory()
    original = chronological_split(build_experiment_matrix(processed))
    shuffled = chronological_split(
        build_experiment_matrix(processed.sample(frac=1.0, random_state=99).reset_index(drop=True))
    )

    def keys(frame: pd.DataFrame) -> set[tuple[str, str, str, int]]:
        return {
            (row.profile_key, row.currency, str(row.target_month), int(row.cutoff_day))
            for row in frame.itertuples()
        }

    assert keys(original.training.context) == keys(shuffled.training.context)
    assert keys(original.validation.context) == keys(shuffled.validation.context)
    counts = original.training.context.groupby(
        ["profile_key", "currency", "target_month"], observed=True
    )["cutoff_day"].nunique()
    assert set(counts) == {2}


def test_unknown_or_unfrozen_selection_cannot_release_test_set(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    split = chronological_split(build_experiment_matrix(processed_frame_factory()))

    with pytest.raises(RuntimeError, match="known candidate"):
        split.release_test_set(
            selected_model_name="unknown",
            model_selection_frozen=True,
            known_model_names=CANDIDATE_MODEL_NAMES,
        )
    with pytest.raises(RuntimeError, match="exactly True"):
        split.release_test_set(
            selected_model_name="ridge",
            model_selection_frozen=False,
            known_model_names=CANDIDATE_MODEL_NAMES,
        )


def test_frozen_known_selection_releases_test_exactly_once(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    split = chronological_split(build_experiment_matrix(processed_frame_factory()))
    test = split.release_test_set(
        selected_model_name="ridge",
        model_selection_frozen=True,
        known_model_names=CANDIDATE_MODEL_NAMES,
    )

    assert len(test.features) == 12
    with pytest.raises(RuntimeError, match="already released once"):
        split.release_test_set(
            selected_model_name="ridge",
            model_selection_frozen=True,
            known_model_names=CANDIDATE_MODEL_NAMES,
        )
