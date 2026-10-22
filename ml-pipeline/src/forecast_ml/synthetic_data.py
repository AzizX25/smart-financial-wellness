"""Deterministic, non-personal transaction generation for local exploration."""

from __future__ import annotations

import calendar
import csv
import random
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

from forecast_ml.deterministic_baseline import shift_month
from forecast_ml.schemas import RAW_COLUMNS, RawTransaction
from forecast_ml.validation import MONEY_SCALE, format_money


@dataclass(frozen=True, slots=True)
class SyntheticDataConfig:
    seed: int = 20260816
    profiles: int = 5
    start_month: date = date(2024, 1, 1)
    end_month: date = date(2025, 12, 1)
    currency: str = "TND"

    def __post_init__(self) -> None:
        if self.profiles < 1:
            raise ValueError("profiles must be positive")
        if self.start_month.day != 1 or self.end_month.day != 1:
            raise ValueError("month boundaries must use the first calendar day")
        if self.start_month > self.end_month:
            raise ValueError("start_month must not be after end_month")
        if (
            len(self.currency) != 3
            or not self.currency.isascii()
            or not self.currency.isalpha()
            or not self.currency.isupper()
        ):
            raise ValueError("currency must be a three-letter uppercase code")


def generate_transactions(config: SyntheticDataConfig) -> list[RawTransaction]:
    randomizer = random.Random(config.seed)
    transactions: list[RawTransaction] = []
    month = config.start_month
    while month <= config.end_month:
        for profile_number in range(1, config.profiles + 1):
            transactions.extend(
                _generate_profile_month(randomizer, config, profile_number, month)
            )
        month = shift_month(month, 1)
    return sorted(
        transactions,
        key=lambda transaction: (
            transaction.profile_key,
            transaction.currency,
            transaction.booking_date,
            transaction.known_date,
            transaction.account_key,
            transaction.direction,
            transaction.amount,
            transaction.source,
            transaction.provider_status or "",
            transaction.category_code or "",
        ),
    )


def write_raw_csv(transactions: list[RawTransaction], path: str | Path) -> None:
    output_path = Path(path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=RAW_COLUMNS, lineterminator="\n")
        writer.writeheader()
        for transaction in transactions:
            writer.writerow(
                {
                    "profile_key": transaction.profile_key,
                    "account_key": transaction.account_key,
                    "booking_date": transaction.booking_date.isoformat(),
                    "known_date": transaction.known_date.isoformat(),
                    "amount": format_money(transaction.amount),
                    "direction": transaction.direction,
                    "currency": transaction.currency,
                    "source": transaction.source,
                    "provider_status": transaction.provider_status or "",
                    "is_internal_transfer": str(transaction.is_internal_transfer).lower(),
                    "category_code": transaction.category_code or "",
                }
            )


def _generate_profile_month(
    randomizer: random.Random,
    config: SyntheticDataConfig,
    profile_number: int,
    month: date,
) -> list[RawTransaction]:
    profile_key = f"profile_{profile_number:04d}"
    checking = f"account_{profile_number:04d}_checking"
    savings = f"account_{profile_number:04d}_savings"
    currency = config.currency
    rows: list[RawTransaction] = []

    salary_cents = randomizer.randint(250_000, 480_000)
    rows.append(_row(profile_key, checking, month, randomizer.randint(1, 3), salary_cents,
                     "CREDIT", currency, "BANK_SYNC", "POSTED", False, "INCOME_SALARY"))

    if randomizer.random() < 0.48:
        rows.append(_row(profile_key, checking, month, randomizer.randint(8, 24),
                         randomizer.randint(25_000, 150_000), "CREDIT", currency,
                         "FILE_IMPORT", None, False, "INCOME_FREELANCE"))

    fixed_expenses = (
        (5, randomizer.randint(60_000, 120_000), "HOUSING", "BANK_SYNC"),
        (12, randomizer.randint(8_000, 22_000), "UTILITIES", "FILE_IMPORT"),
        (18, randomizer.randint(2_000, 8_000), "SUBSCRIPTIONS", "BANK_SYNC"),
    )
    for day, cents, category, source in fixed_expenses:
        rows.append(_row(profile_key, checking, month, day, cents, "DEBIT", currency,
                         source, "POSTED" if source == "BANK_SYNC" else None, False, category))

    for _ in range(randomizer.randint(5, 10)):
        source = randomizer.choice(("MANUAL", "FILE_IMPORT", "BANK_SYNC"))
        rows.append(_row(
            profile_key,
            checking,
            month,
            randomizer.randint(2, 28),
            randomizer.randint(800, 18_000),
            "DEBIT",
            currency,
            source,
            "POSTED" if source == "BANK_SYNC" else None,
            False,
            randomizer.choice(("FOOD", "TRANSPORT", "LEISURE", "SHOPPING")),
        ))

    if month.month in {7, 12}:
        rows.append(_row(profile_key, checking, month, 20, randomizer.randint(15_000, 70_000),
                         "DEBIT", currency, "MANUAL", None, False, "SEASONAL"))
    if randomizer.random() < 0.16:
        rows.append(_row(profile_key, checking, month, randomizer.randint(10, 27),
                         randomizer.randint(30_000, 180_000), "DEBIT", currency,
                         "BANK_SYNC", "POSTED", False, "OCCASIONAL_SHOCK"))

    if randomizer.random() < 0.42:
        transfer_cents = randomizer.randint(10_000, 60_000)
        transfer_day = randomizer.randint(6, 22)
        rows.append(_row(profile_key, checking, month, transfer_day, transfer_cents,
                         "DEBIT", currency, "BANK_SYNC", "POSTED", True, "TRANSFER_OUT"))
        rows.append(_row(profile_key, savings, month, transfer_day, transfer_cents,
                         "CREDIT", currency, "BANK_SYNC", "POSTED", True, "TRANSFER_IN"))

    for provider_status in ("PENDING", "REVERSED"):
        if randomizer.random() < 0.35:
            rows.append(_row(profile_key, checking, month, randomizer.randint(20, 28),
                             randomizer.randint(1_000, 12_000), "DEBIT", currency,
                             "BANK_SYNC", provider_status, False, "SHOPPING"))
    return rows


def _row(
    profile_key: str,
    account_key: str,
    month: date,
    day: int,
    cents: int,
    direction: str,
    currency: str,
    source: str,
    provider_status: str | None,
    is_internal_transfer: bool,
    category_code: str | None,
) -> RawTransaction:
    maximum_day = calendar.monthrange(month.year, month.month)[1]
    booking_date = month.replace(day=min(day, maximum_day))
    if source == "BANK_SYNC":
        delay_days = cents % 3
    elif source == "FILE_IMPORT":
        delay_days = cents % 2
    else:
        delay_days = 0
    amount = (Decimal(cents) / Decimal(100)).quantize(MONEY_SCALE)
    return RawTransaction(
        profile_key=profile_key,
        account_key=account_key,
        booking_date=booking_date,
        known_date=booking_date + timedelta(days=delay_days),
        amount=amount,
        direction=direction,
        currency=currency,
        source=source,
        provider_status=provider_status,
        is_internal_transfer=is_internal_transfer,
        category_code=category_code,
    )
