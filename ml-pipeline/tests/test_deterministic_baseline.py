from __future__ import annotations

import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from forecast_ml.deterministic_baseline import (
    METHOD,
    METHOD_VERSION,
    calculate_deterministic_baseline,
)
from forecast_ml.schemas import RawTransaction

CASES = json.loads(
    (Path(__file__).parent / "fixtures" / "deterministic_baseline_cases.json").read_text(
        encoding="utf-8"
    )
)


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_language_neutral_java_python_parity_cases(case: dict[str, object]) -> None:
    target_month = date.fromisoformat(str(case["target_month"]) + "-01")
    transactions = [
        RawTransaction(
            "profile_0001",
            "account_0001",
            date.fromisoformat(row["booking_date"]),
            date.fromisoformat(row["booking_date"]),
            Decimal(row["amount"]),
            row["direction"],
            "TND",
            "MANUAL",
            None,
            False,
            None,
        )
        for row in case["transactions"]
    ]

    result = calculate_deterministic_baseline(
        transactions,
        profile_key="profile_0001",
        currency="TND",
        target_month=target_month,
        cutoff_day=case["cutoff_day"],
    )

    expected = case["expected"]
    assert result.expected_remaining_income == Decimal(expected["income"])
    assert result.expected_remaining_expense == Decimal(expected["expense"])
    assert result.expected_remaining_net_flow == Decimal(expected["net"])
    assert result.usable_history_count == expected["usable_months"]
    assert result.fallback_reason.value == expected["fallback"]
    assert result.method == METHOD
    assert result.method_version == METHOD_VERSION
