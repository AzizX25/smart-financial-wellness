from __future__ import annotations

from datetime import date
from decimal import Decimal

from forecast_ml.eligibility import ExclusionReason, exclusion_reason, is_cash_flow_eligible
from forecast_ml.schemas import RawTransaction


def transaction(
    source: str,
    provider_status: str | None = None,
    *,
    internal: bool = False,
    category: str | None = None,
) -> RawTransaction:
    return RawTransaction(
        "profile_0001",
        "account_0001",
        date(2026, 7, 1),
        date(2026, 7, 1),
        Decimal("10.0000"),
        "DEBIT",
        "TND",
        source,
        provider_status,
        internal,
        category,
    )


def test_manual_file_and_posted_bank_rows_are_eligible() -> None:
    assert is_cash_flow_eligible(transaction("MANUAL"))
    assert is_cash_flow_eligible(transaction("FILE_IMPORT"))
    assert is_cash_flow_eligible(transaction("BANK_SYNC", "POSTED"))


def test_internal_and_non_posted_bank_rows_are_excluded() -> None:
    assert exclusion_reason(transaction("MANUAL", internal=True)) == ExclusionReason.INTERNAL_TRANSFER
    for status in ("PENDING", "REVERSED", "UNKNOWN"):
        assert exclusion_reason(transaction("BANK_SYNC", status)) == ExclusionReason.BANK_NOT_POSTED


def test_category_code_does_not_control_cash_flow_eligibility() -> None:
    for category in (None, "UNCATEGORIZED", "TRANSFER_OUT", "NEUTRAL", "INACTIVE_CATEGORY"):
        assert is_cash_flow_eligible(transaction("MANUAL", category=category))
