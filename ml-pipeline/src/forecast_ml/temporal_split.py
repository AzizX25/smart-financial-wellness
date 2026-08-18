"""Chronological experiment partitions with an explicit final-test guard."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable

import numpy as np
import pandas as pd

from forecast_ml.feature_matrix import ExperimentMatrix

_MONTH_PATTERN = re.compile(r"^[0-9]{4}-(?:0[1-9]|1[0-2])$")


def _as_period(value: str, field: str) -> pd.Period:
    if not isinstance(value, str) or not _MONTH_PATTERN.fullmatch(value):
        raise ValueError(f"{field} month must use YYYY-MM")
    return pd.Period(value, freq="M")


@dataclass(frozen=True, slots=True)
class MonthRange:
    start: str
    end: str

    def __post_init__(self) -> None:
        start = _as_period(self.start, "start")
        end = _as_period(self.end, "end")
        if start > end:
            raise ValueError("month range start must not be after end")

    @property
    def start_period(self) -> pd.Period:
        return pd.Period(self.start, freq="M")

    @property
    def end_period(self) -> pd.Period:
        return pd.Period(self.end, freq="M")

    def contains(self, month: pd.Period) -> bool:
        return self.start_period <= month <= self.end_period


@dataclass(frozen=True, slots=True)
class TemporalBoundaries:
    training: MonthRange = MonthRange("2024-01", "2025-06")
    validation: MonthRange = MonthRange("2025-07", "2025-09")
    test: MonthRange = MonthRange("2025-10", "2025-12")

    def __post_init__(self) -> None:
        if self.training.end_period >= self.validation.start_period:
            raise ValueError("training and validation periods must be ordered and non-overlapping")
        if self.validation.end_period >= self.test.start_period:
            raise ValueError("validation and test periods must be ordered and non-overlapping")


class GuardedTemporalSplit:
    """Expose development partitions while withholding final-test rows and labels."""

    def __init__(
        self,
        training: ExperimentMatrix,
        validation: ExperimentMatrix,
        test: ExperimentMatrix,
    ) -> None:
        self.training = training
        self.validation = validation
        self.__test = test
        self.__test_released = False

    def partition_summary(self) -> pd.DataFrame:
        rows = []
        for name, matrix in (
            ("training", self.training),
            ("validation", self.validation),
            ("test_locked", self.__test),
        ):
            groups = matrix.context[
                ["profile_key", "currency", "target_month"]
            ].drop_duplicates()
            rows.append(
                {
                    "partition": name,
                    "row_count": len(matrix.features),
                    "profile_month_group_count": len(groups),
                }
            )
        return pd.DataFrame(rows)

    def release_test_set(
        self,
        *,
        selected_model_name: str | None,
        model_selection_frozen: bool,
        known_model_names: Iterable[str],
    ) -> ExperimentMatrix:
        known = frozenset(known_model_names)
        if selected_model_name not in known:
            raise RuntimeError(
                "Final test is locked: choose a known candidate after reviewing validation results."
            )
        if model_selection_frozen is not True:
            raise RuntimeError(
                "Final test is locked: set MODEL_SELECTION_FROZEN to exactly True only after "
                "recording and freezing the validation-based model decision."
            )
        if self.__test_released:
            raise RuntimeError(
                "Final test rows were already released once for this experiment session."
            )
        self.__test_released = True
        return self.__test


def chronological_split(
    matrix: ExperimentMatrix,
    boundaries: TemporalBoundaries = TemporalBoundaries(),
) -> GuardedTemporalSplit:
    """Split by target month and reject gaps, overlap, or empty partitions."""
    months = matrix.context["target_month"]
    if not all(isinstance(month, pd.Period) and month.freqstr == "M" for month in months):
        raise ValueError("target_month context must contain monthly pandas Period values")

    assignments = pd.Series(index=matrix.context.index, dtype="object")
    ranges = {
        "training": boundaries.training,
        "validation": boundaries.validation,
        "test": boundaries.test,
    }
    for partition_name, month_range in ranges.items():
        mask = months.map(month_range.contains)
        if assignments.loc[mask].notna().any():
            raise ValueError("temporal partitions overlap")
        assignments.loc[mask] = partition_name

    if assignments.isna().any():
        uncovered = sorted({str(month) for month in months.loc[assignments.isna()]})
        raise ValueError(
            "target months fall outside the configured partitions: " + ", ".join(uncovered)
        )

    grouped = matrix.context.assign(_partition=assignments).groupby(
        ["profile_key", "currency", "target_month"],
        observed=True,
    )["_partition"].nunique()
    if (grouped > 1).any():
        raise ValueError("a profile-month group crosses temporal partitions")

    partitions: dict[str, ExperimentMatrix] = {}
    for name in ranges:
        positions = np.flatnonzero(assignments.to_numpy() == name)
        if len(positions) == 0:
            raise ValueError(f"{name} partition is empty for the configured boundaries")
        partitions[name] = matrix.take(positions)

    return GuardedTemporalSplit(
        partitions["training"],
        partitions["validation"],
        partitions["test"],
    )
