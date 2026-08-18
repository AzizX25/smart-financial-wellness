from __future__ import annotations

from decimal import Decimal

import pytest

from forecast_ml.schemas import RAW_COLUMNS
from forecast_ml.validation import ContractValidationError, parse_money, validate_raw_row


def valid_row() -> dict[str, str]:
    return dict(zip(RAW_COLUMNS, (
        "profile_0001",
        "account_0001_checking",
        "2026-07-15",
        "2026-07-16",
        "12.3400",
        "DEBIT",
        "TND",
        "BANK_SYNC",
        "POSTED",
        "false",
        "FOOD",
    )))


def test_validates_and_normalizes_strict_raw_contract() -> None:
    transaction = validate_raw_row(valid_row())

    assert transaction.amount == Decimal("12.3400")
    assert transaction.amount.as_tuple().exponent == -4
    assert transaction.provider_status == "POSTED"
    assert transaction.is_internal_transfer is False


@pytest.mark.parametrize("amount", ["-1", "0", "1.00001", "1e2", "NaN", ""])
def test_rejects_invalid_or_over_scale_money(amount: str) -> None:
    row = valid_row()
    row["amount"] = amount

    with pytest.raises(ContractValidationError):
        validate_raw_row(row)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("booking_date", "2026-02-30"),
        ("currency", "tnd"),
        ("direction", "INCOME"),
        ("source", "API"),
        ("is_internal_transfer", "yes"),
        ("profile_key", "person@example.test"),
        ("profile_key", "123456"),
        ("profile_key", "550e8400-e29b-41d4-a716-446655440000"),
    ],
)
def test_rejects_malformed_contract_fields(field: str, value: str) -> None:
    row = valid_row()
    row[field] = value

    with pytest.raises(ContractValidationError):
        validate_raw_row(row)


def test_rejects_sensitive_and_other_unexpected_columns() -> None:
    sensitive = valid_row()
    sensitive["email"] = "person@example.test"
    with pytest.raises(ContractValidationError, match="forbidden sensitive columns"):
        validate_raw_row(sensitive)

    unexpected = valid_row()
    unexpected["debug_value"] = "x"
    with pytest.raises(ContractValidationError, match="unexpected"):
        validate_raw_row(unexpected)


def test_provider_status_must_match_source_provenance() -> None:
    manual = valid_row()
    manual["source"] = "MANUAL"
    with pytest.raises(ContractValidationError, match="cannot have provider_status"):
        validate_raw_row(manual)

    bank = valid_row()
    bank["provider_status"] = ""
    with pytest.raises(ContractValidationError, match="require provider_status"):
        validate_raw_row(bank)


def test_rejects_a_known_date_before_booking() -> None:
    row = valid_row()
    row["known_date"] = "2026-07-14"

    with pytest.raises(ContractValidationError, match="cannot precede"):
        validate_raw_row(row)


def test_money_parser_preserves_four_decimal_scale() -> None:
    assert parse_money("1.2") == Decimal("1.2000")
    assert parse_money("1.2").as_tuple().exponent == -4
