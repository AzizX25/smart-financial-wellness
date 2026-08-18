"""Stable raw and processed dataset contracts."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Final

RAW_COLUMNS: Final[tuple[str, ...]] = (
    "profile_key",
    "account_key",
    "booking_date",
    "known_date",
    "amount",
    "direction",
    "currency",
    "source",
    "provider_status",
    "is_internal_transfer",
    "category_code",
)

PROCESSED_COLUMNS: Final[tuple[str, ...]] = (
    "dataset_version",
    "profile_key",
    "currency",
    "target_month",
    "cutoff_date",
    "calendar_month",
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
    "deterministic_fallback_reason",
    "actual_remaining_income",
    "actual_remaining_expense",
    "actual_remaining_net_flow",
    "residual_target",
)


@dataclass(frozen=True, slots=True)
class RawTransaction:
    profile_key: str
    account_key: str
    booking_date: date
    known_date: date
    amount: Decimal
    direction: str
    currency: str
    source: str
    provider_status: str | None
    is_internal_transfer: bool
    category_code: str | None


@dataclass(frozen=True, slots=True)
class ProcessedExample:
    dataset_version: str
    profile_key: str
    currency: str
    target_month: str
    cutoff_date: date
    calendar_month: int
    cutoff_day: int
    days_remaining: int
    month_to_date_credit: Decimal
    month_to_date_debit: Decimal
    month_to_date_net_flow: Decimal
    month_to_date_transaction_count: int
    trailing_six_month_credit: Decimal
    trailing_six_month_debit: Decimal
    trailing_six_month_net_flow: Decimal
    historical_remaining_net_flow_range: Decimal
    deterministic_remaining_income: Decimal
    deterministic_remaining_expense: Decimal
    deterministic_remaining_net_flow: Decimal
    usable_history_count: int
    deterministic_fallback_reason: str
    actual_remaining_income: Decimal
    actual_remaining_expense: Decimal
    actual_remaining_net_flow: Decimal
    residual_target: Decimal
