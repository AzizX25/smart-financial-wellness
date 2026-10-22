"""Python parity implementation of the Java remaining-period baseline."""

from __future__ import annotations

import calendar
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, ROUND_HALF_EVEN
from enum import Enum

from forecast_ml.eligibility import is_cash_flow_eligible
from forecast_ml.schemas import RawTransaction
from forecast_ml.validation import MONEY_SCALE, ZERO

METHOD = "HISTORICAL_REMAINING_PERIOD_AVERAGE"
METHOD_VERSION = "1"
HISTORY_MONTHS = 6


class FallbackReason(str, Enum):
    NONE = "NONE"
    LIMITED_HISTORY = "LIMITED_HISTORY"
    NO_USABLE_HISTORY = "NO_USABLE_HISTORY"


@dataclass(frozen=True, slots=True)
class BaselineResult:
    expected_remaining_income: Decimal
    expected_remaining_expense: Decimal
    expected_remaining_net_flow: Decimal
    method: str
    method_version: str
    history_start: date
    history_end: date
    usable_history_count: int
    fallback_reason: FallbackReason
    usable_month_net_flows: tuple[Decimal, ...]


def calculate_deterministic_baseline(
    transactions: list[RawTransaction],
    *,
    profile_key: str,
    currency: str,
    target_month: date,
    cutoff_day: int,
) -> BaselineResult:
    target_first = target_month.replace(day=1)
    if not 1 <= cutoff_day <= calendar.monthrange(target_first.year, target_first.month)[1]:
        raise ValueError("cutoff_day must exist in the target month")

    history_start = shift_month(target_first, -HISTORY_MONTHS)
    history_end = shift_month(target_first, -1)
    history_end = history_end.replace(
        day=calendar.monthrange(history_end.year, history_end.month)[1]
    )
    scoped = [
        transaction
        for transaction in transactions
        if transaction.profile_key == profile_key
        and transaction.currency == currency
        and history_start <= transaction.booking_date < target_first
        and is_cash_flow_eligible(transaction)
    ]

    total_income = ZERO
    total_expense = ZERO
    usable_month_net_flows: list[Decimal] = []
    for offset in range(HISTORY_MONTHS):
        month_start = shift_month(history_start, offset)
        month_end_day = calendar.monthrange(month_start.year, month_start.month)[1]
        month_end = month_start.replace(day=month_end_day)
        month_transactions = [
            transaction
            for transaction in scoped
            if month_start <= transaction.booking_date <= month_end
        ]
        if not month_transactions:
            continue

        analogous_cutoff = month_start.replace(day=min(cutoff_day, month_end_day))
        remaining_income = sum(
            (
                transaction.amount
                for transaction in month_transactions
                if transaction.booking_date > analogous_cutoff
                and transaction.direction == "CREDIT"
            ),
            ZERO,
        )
        remaining_expense = sum(
            (
                transaction.amount
                for transaction in month_transactions
                if transaction.booking_date > analogous_cutoff
                and transaction.direction == "DEBIT"
            ),
            ZERO,
        )
        total_income += remaining_income
        total_expense += remaining_expense
        usable_month_net_flows.append((remaining_income - remaining_expense).quantize(MONEY_SCALE))

    usable_count = len(usable_month_net_flows)
    expected_income = _average(total_income, usable_count)
    expected_expense = _average(total_expense, usable_count)
    expected_net = (expected_income - expected_expense).quantize(MONEY_SCALE)
    if usable_count == 0:
        fallback = FallbackReason.NO_USABLE_HISTORY
    elif usable_count <= 2:
        fallback = FallbackReason.LIMITED_HISTORY
    else:
        fallback = FallbackReason.NONE

    return BaselineResult(
        expected_remaining_income=expected_income,
        expected_remaining_expense=expected_expense,
        expected_remaining_net_flow=expected_net,
        method=METHOD,
        method_version=METHOD_VERSION,
        history_start=history_start,
        history_end=history_end,
        usable_history_count=usable_count,
        fallback_reason=fallback,
        usable_month_net_flows=tuple(usable_month_net_flows),
    )


def shift_month(month_start: date, offset: int) -> date:
    absolute_month = month_start.year * 12 + (month_start.month - 1) + offset
    year, zero_based_month = divmod(absolute_month, 12)
    return date(year, zero_based_month + 1, 1)


def _average(total: Decimal, divisor: int) -> Decimal:
    if divisor == 0:
        return ZERO
    return (total / Decimal(divisor)).quantize(MONEY_SCALE, rounding=ROUND_HALF_EVEN)
