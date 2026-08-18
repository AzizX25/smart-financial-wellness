from __future__ import annotations

from datetime import date

import pytest

from forecast_ml.synthetic_data import (
    SyntheticDataConfig,
    generate_transactions,
    write_raw_csv,
)
from forecast_ml.validation import read_raw_csv


def test_same_seed_produces_identical_rows_and_csv_bytes(tmp_path) -> None:
    config = SyntheticDataConfig(
        seed=42,
        profiles=2,
        start_month=date(2025, 1, 1),
        end_month=date(2025, 6, 1),
        currency="TND",
    )

    first = generate_transactions(config)
    second = generate_transactions(config)
    first_path = tmp_path / "first.csv"
    second_path = tmp_path / "second.csv"
    write_raw_csv(first, first_path)
    write_raw_csv(second, second_path)

    assert first == second
    assert first_path.read_bytes() == second_path.read_bytes()
    assert read_raw_csv(first_path) == first


def test_generator_covers_explainable_patterns_without_personal_data() -> None:
    transactions = generate_transactions(
        SyntheticDataConfig(
            seed=20260816,
            profiles=3,
            start_month=date(2024, 1, 1),
            end_month=date(2025, 12, 1),
        )
    )

    categories = {row.category_code for row in transactions}
    statuses = {row.provider_status for row in transactions}
    sources = {row.source for row in transactions}

    assert {"INCOME_SALARY", "INCOME_FREELANCE", "HOUSING", "SEASONAL"} <= categories
    assert {"MANUAL", "FILE_IMPORT", "BANK_SYNC"} <= sources
    assert {"POSTED", "PENDING", "REVERSED"} <= statuses
    assert any(row.is_internal_transfer for row in transactions)
    assert all("@" not in row.profile_key and "@" not in row.account_key for row in transactions)


@pytest.mark.parametrize("currency", ["tnd", "TN", "TNDX", "T1D", "\u00c9UR"])
def test_generator_rejects_invalid_currency(currency: str) -> None:
    with pytest.raises(ValueError, match="three-letter uppercase"):
        SyntheticDataConfig(currency=currency)
