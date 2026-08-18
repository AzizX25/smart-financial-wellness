from __future__ import annotations

import csv
from datetime import date
from decimal import Decimal

from forecast_ml.dataset_builder import (
    build_training_dataset,
    feature_values,
    write_processed_csv,
)
from forecast_ml.schemas import PROCESSED_COLUMNS, ProcessedExample, RawTransaction


def transaction(
    booking_date: date,
    amount: str,
    direction: str,
    *,
    profile: str = "profile_0001",
    currency: str = "TND",
    source: str = "MANUAL",
    provider_status: str | None = None,
    internal: bool = False,
    known_date: date | None = None,
) -> RawTransaction:
    return RawTransaction(
        profile_key=profile,
        account_key=f"account_{profile[-4:]}",
        booking_date=booking_date,
        known_date=known_date or booking_date,
        amount=Decimal(amount),
        direction=direction,
        currency=currency,
        source=source,
        provider_status=provider_status,
        is_internal_transfer=internal,
        category_code=None,
    )


def example_for(
    transactions: list[RawTransaction],
    *,
    month: str,
    profile: str = "profile_0001",
    currency: str = "TND",
    cutoff: int = 15,
    as_of: date = date(2026, 10, 1),
) -> ProcessedExample:
    examples = build_training_dataset(
        transactions,
        as_of_date=as_of,
        cutoff_days=(cutoff,),
    )
    return next(
        row
        for row in examples
        if row.profile_key == profile
        and row.currency == currency
        and row.target_month == month
        and row.cutoff_day == cutoff
    )


def test_post_cutoff_change_only_changes_outcomes_and_target() -> None:
    initial = [
        transaction(date(2026, 7, 20), "100.0000", "CREDIT"),
        transaction(date(2026, 8, 10), "10.0000", "DEBIT"),
        transaction(date(2026, 8, 20), "20.0000", "DEBIT"),
    ]
    changed = [*initial[:-1], transaction(date(2026, 8, 20), "200.0000", "DEBIT")]

    before = example_for(initial, month="2026-08")
    after = example_for(changed, month="2026-08")

    assert feature_values(before) == feature_values(after)
    assert before.deterministic_remaining_net_flow == Decimal("100.0000")
    assert after.deterministic_remaining_net_flow == Decimal("100.0000")
    assert before.actual_remaining_net_flow == Decimal("-20.0000")
    assert after.actual_remaining_net_flow == Decimal("-200.0000")
    assert before.residual_target == Decimal("-120.0000")
    assert after.residual_target == Decimal("-300.0000")


def test_target_month_and_future_month_are_never_baseline_history() -> None:
    transactions = [
        transaction(date(2026, 7, 20), "30.0000", "CREDIT"),
        transaction(date(2026, 8, 20), "900.0000", "CREDIT"),
        transaction(date(2026, 9, 20), "700.0000", "CREDIT"),
    ]

    august = example_for(transactions, month="2026-08")

    assert august.deterministic_remaining_income == Decimal("30.0000")
    assert august.usable_history_count == 1


def test_profile_and_currency_are_strictly_isolated() -> None:
    transactions = [
        transaction(date(2026, 7, 20), "40.0000", "CREDIT"),
        transaction(date(2026, 7, 20), "500.0000", "CREDIT", profile="profile_0002"),
        transaction(date(2026, 7, 20), "800.0000", "CREDIT", currency="EUR"),
        transaction(date(2026, 8, 20), "10.0000", "DEBIT"),
    ]

    tnd_profile_one = example_for(transactions, month="2026-08")

    assert tnd_profile_one.deterministic_remaining_income == Decimal("40.0000")
    assert tnd_profile_one.actual_remaining_expense == Decimal("10.0000")


def test_target_uses_only_eligible_cash_flow_transactions() -> None:
    transactions = [
        transaction(date(2026, 8, 16), "10.0000", "CREDIT", source="MANUAL"),
        transaction(date(2026, 8, 17), "2.0000", "CREDIT", source="FILE_IMPORT"),
        transaction(
            date(2026, 8, 18),
            "3.0000",
            "CREDIT",
            source="BANK_SYNC",
            provider_status="POSTED",
        ),
        transaction(
            date(2026, 8, 19),
            "100.0000",
            "CREDIT",
            source="BANK_SYNC",
            provider_status="PENDING",
        ),
        transaction(
            date(2026, 8, 20),
            "100.0000",
            "DEBIT",
            source="BANK_SYNC",
            provider_status="REVERSED",
        ),
        transaction(date(2026, 8, 21), "50.0000", "DEBIT", internal=True),
    ]

    row = example_for(transactions, month="2026-08")

    assert row.actual_remaining_income == Decimal("15.0000")
    assert row.actual_remaining_expense == Decimal("0.0000")
    assert row.actual_remaining_net_flow == Decimal("15.0000")


def test_incomplete_target_months_are_excluded() -> None:
    transactions = [transaction(date(2026, 8, 10), "10.0000", "DEBIT")]

    rows = build_training_dataset(
        transactions,
        as_of_date=date(2026, 8, 20),
        cutoff_days=(5, 10, 15),
    )

    assert rows == []


def test_late_known_pre_cutoff_transaction_is_not_an_input_feature() -> None:
    transactions = [
        transaction(
            date(2026, 8, 10),
            "90.0000",
            "DEBIT",
            known_date=date(2026, 8, 18),
        ),
        transaction(date(2026, 8, 20), "10.0000", "DEBIT"),
    ]

    row = example_for(transactions, month="2026-08")

    assert row.month_to_date_debit == Decimal("0.0000")
    assert row.month_to_date_transaction_count == 0


def test_transactions_not_known_by_dataset_as_of_date_are_excluded() -> None:
    transactions = [
        transaction(date(2026, 8, 20), "10.0000", "DEBIT"),
        transaction(
            date(2026, 8, 21),
            "900.0000",
            "DEBIT",
            known_date=date(2026, 10, 2),
        ),
    ]

    row = example_for(
        transactions,
        month="2026-08",
        as_of=date(2026, 10, 1),
    )

    assert row.actual_remaining_expense == Decimal("10.0000")


def test_residual_identity_and_four_decimal_scale_are_preserved() -> None:
    transactions = [
        transaction(date(2026, 6, 16), "1.0000", "CREDIT"),
        transaction(date(2026, 7, 16), "2.0001", "DEBIT"),
        transaction(date(2026, 8, 16), "3.0001", "DEBIT"),
    ]

    row = example_for(transactions, month="2026-08")

    assert row.deterministic_remaining_net_flow == Decimal("-0.5000")
    assert row.actual_remaining_net_flow == Decimal("-3.0001")
    assert row.residual_target == Decimal("-2.5001")
    assert row.residual_target == (
        row.actual_remaining_net_flow - row.deterministic_remaining_net_flow
    )
    assert all(
        value.as_tuple().exponent == -4
        for value in (
            row.month_to_date_credit,
            row.month_to_date_debit,
            row.deterministic_remaining_net_flow,
            row.actual_remaining_net_flow,
            row.residual_target,
        )
    )


def test_processed_csv_uses_stable_header_and_money_scale(tmp_path) -> None:
    row = example_for(
        [transaction(date(2026, 8, 20), "3.1000", "DEBIT")],
        month="2026-08",
    )
    output = tmp_path / "processed.csv"

    write_processed_csv([row], output)

    with output.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        serialized = next(reader)
        assert tuple(reader.fieldnames or ()) == PROCESSED_COLUMNS
    assert serialized["actual_remaining_expense"] == "3.1000"
    assert serialized["residual_target"] == "-3.1000"
