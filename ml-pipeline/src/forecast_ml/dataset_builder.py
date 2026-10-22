"""Leakage-safe construction of residual-target training examples."""

from __future__ import annotations

import calendar
import csv
from collections import defaultdict
from dataclasses import fields
from datetime import date
from decimal import Decimal
from pathlib import Path

from forecast_ml.deterministic_baseline import (
    calculate_deterministic_baseline,
    shift_month,
)
from forecast_ml.eligibility import is_cash_flow_eligible
from forecast_ml.schemas import PROCESSED_COLUMNS, ProcessedExample, RawTransaction
from forecast_ml.validation import MONEY_SCALE, ZERO, format_money

DATASET_VERSION = "forecast-residual-v1"
DEFAULT_CUTOFF_DAYS = (5, 10, 15, 20, 25)

_MONEY_FIELDS = frozenset(
    {
        "month_to_date_credit",
        "month_to_date_debit",
        "month_to_date_net_flow",
        "trailing_six_month_credit",
        "trailing_six_month_debit",
        "trailing_six_month_net_flow",
        "historical_remaining_net_flow_range",
        "deterministic_remaining_income",
        "deterministic_remaining_expense",
        "deterministic_remaining_net_flow",
        "actual_remaining_income",
        "actual_remaining_expense",
        "actual_remaining_net_flow",
        "residual_target",
    }
)


def build_training_dataset(
    transactions: list[RawTransaction],
    *,
    as_of_date: date,
    cutoff_days: tuple[int, ...] = DEFAULT_CUTOFF_DAYS,
) -> list[ProcessedExample]:
    if not cutoff_days or any(day < 1 or day > 31 for day in cutoff_days):
        raise ValueError("cutoff_days must contain values between 1 and 31")
    normalized_cutoffs = tuple(sorted(set(cutoff_days)))
    grouped: dict[tuple[str, str], list[RawTransaction]] = defaultdict(list)
    for transaction in transactions:
        if transaction.known_date <= as_of_date:
            grouped[(transaction.profile_key, transaction.currency)].append(transaction)

    examples: list[ProcessedExample] = []
    for (profile_key, currency), scoped in sorted(grouped.items()):
        first_month = min(transaction.booking_date for transaction in scoped).replace(day=1)
        last_month = max(transaction.booking_date for transaction in scoped).replace(day=1)
        target_month = first_month
        while target_month <= last_month:
            target_end = target_month.replace(
                day=calendar.monthrange(target_month.year, target_month.month)[1]
            )
            if target_end < as_of_date:
                for cutoff_day in normalized_cutoffs:
                    if cutoff_day <= target_end.day:
                        examples.append(
                            _build_example(
                                transactions,
                                scoped,
                                profile_key,
                                currency,
                                target_month,
                                target_end,
                                cutoff_day,
                            )
                        )
            target_month = shift_month(target_month, 1)
    return examples


def write_processed_csv(examples: list[ProcessedExample], path: str | Path) -> None:
    output_path = Path(path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=PROCESSED_COLUMNS, lineterminator="\n")
        writer.writeheader()
        for example in examples:
            writer.writerow(_serialize_example(example))


def feature_values(example: ProcessedExample) -> dict[str, object]:
    """Return prediction-time values only, excluding labels and actual outcomes."""
    excluded = {
        "actual_remaining_income",
        "actual_remaining_expense",
        "actual_remaining_net_flow",
        "residual_target",
    }
    return {
        field.name: getattr(example, field.name)
        for field in fields(example)
        if field.name not in excluded
    }


def _build_example(
    all_transactions: list[RawTransaction],
    scoped_transactions: list[RawTransaction],
    profile_key: str,
    currency: str,
    target_month: date,
    target_end: date,
    cutoff_day: int,
) -> ProcessedExample:
    cutoff_date = target_month.replace(day=cutoff_day)
    eligible_scoped = [
        transaction
        for transaction in scoped_transactions
        if is_cash_flow_eligible(transaction)
    ]
    target_transactions = [
        transaction
        for transaction in eligible_scoped
        if target_month <= transaction.booking_date <= target_end
    ]
    month_to_date = [
        transaction
        for transaction in target_transactions
        if transaction.booking_date <= cutoff_date
        and transaction.known_date <= cutoff_date
    ]
    actual_remaining = [
        transaction
        for transaction in target_transactions
        if transaction.booking_date > cutoff_date
    ]

    baseline = calculate_deterministic_baseline(
        [
            transaction
            for transaction in all_transactions
            if transaction.known_date <= cutoff_date
        ],
        profile_key=profile_key,
        currency=currency,
        target_month=target_month,
        cutoff_day=cutoff_day,
    )
    history_start = shift_month(target_month, -6)
    trailing = [
        transaction
        for transaction in eligible_scoped
        if history_start <= transaction.booking_date < target_month
        and transaction.known_date <= cutoff_date
    ]
    mtd_credit, mtd_debit = _cash_flow_totals(month_to_date)
    trailing_credit, trailing_debit = _cash_flow_totals(trailing)
    actual_income, actual_expense = _cash_flow_totals(actual_remaining)
    actual_net = (actual_income - actual_expense).quantize(MONEY_SCALE)
    residual_target = (
        actual_net - baseline.expected_remaining_net_flow
    ).quantize(MONEY_SCALE)
    variability = _range(baseline.usable_month_net_flows)

    return ProcessedExample(
        dataset_version=DATASET_VERSION,
        profile_key=profile_key,
        currency=currency,
        target_month=f"{target_month.year:04d}-{target_month.month:02d}",
        cutoff_date=cutoff_date,
        calendar_month=target_month.month,
        cutoff_day=cutoff_day,
        days_remaining=(target_end - cutoff_date).days,
        month_to_date_credit=mtd_credit,
        month_to_date_debit=mtd_debit,
        month_to_date_net_flow=(mtd_credit - mtd_debit).quantize(MONEY_SCALE),
        month_to_date_transaction_count=len(month_to_date),
        trailing_six_month_credit=trailing_credit,
        trailing_six_month_debit=trailing_debit,
        trailing_six_month_net_flow=(trailing_credit - trailing_debit).quantize(MONEY_SCALE),
        historical_remaining_net_flow_range=variability,
        deterministic_remaining_income=baseline.expected_remaining_income,
        deterministic_remaining_expense=baseline.expected_remaining_expense,
        deterministic_remaining_net_flow=baseline.expected_remaining_net_flow,
        usable_history_count=baseline.usable_history_count,
        deterministic_fallback_reason=baseline.fallback_reason.value,
        actual_remaining_income=actual_income,
        actual_remaining_expense=actual_expense,
        actual_remaining_net_flow=actual_net,
        residual_target=residual_target,
    )


def _cash_flow_totals(transactions: list[RawTransaction]) -> tuple[Decimal, Decimal]:
    credits = sum(
        (transaction.amount for transaction in transactions if transaction.direction == "CREDIT"),
        ZERO,
    )
    debits = sum(
        (transaction.amount for transaction in transactions if transaction.direction == "DEBIT"),
        ZERO,
    )
    return credits.quantize(MONEY_SCALE), debits.quantize(MONEY_SCALE)


def _range(values: tuple[Decimal, ...]) -> Decimal:
    if not values:
        return ZERO
    return (max(values) - min(values)).quantize(MONEY_SCALE)


def _serialize_example(example: ProcessedExample) -> dict[str, str | int]:
    serialized: dict[str, str | int] = {}
    for column in PROCESSED_COLUMNS:
        value = getattr(example, column)
        if column in _MONEY_FIELDS:
            serialized[column] = format_money(value)
        elif isinstance(value, date):
            serialized[column] = value.isoformat()
        else:
            serialized[column] = value
    return serialized
