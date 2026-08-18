"""Strict privacy-oriented validation for pseudonymized transaction exports."""

from __future__ import annotations

import csv
import re
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Mapping

from forecast_ml.schemas import RAW_COLUMNS, RawTransaction

MONEY_SCALE = Decimal("0.0001")
ZERO = Decimal("0.0000")

_KEY_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{0,63}$")
_UUID_PATTERN = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)
_CURRENCY_PATTERN = re.compile(r"^[A-Z]{3}$")
_STATUS_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,39}$")
_CATEGORY_PATTERN = re.compile(r"^[A-Z0-9_:-]{1,80}$")
_AMOUNT_PATTERN = re.compile(r"^(?:0|[1-9][0-9]{0,14})(?:\.[0-9]{1,4})?$")

_DIRECTIONS = frozenset({"DEBIT", "CREDIT"})
_SOURCES = frozenset({"MANUAL", "FILE_IMPORT", "BANK_SYNC"})
_SENSITIVE_COLUMN_FRAGMENTS = (
    "email",
    "name",
    "user_id",
    "account_number",
    "account_identifier",
    "iban",
    "token",
    "password",
    "secret",
    "description",
    "merchant",
    "counterparty",
    "address",
    "phone",
)


class ContractValidationError(ValueError):
    """Raised when a raw export violates its strict contract."""


def parse_money(raw: str, field: str = "amount", *, strictly_positive: bool = True) -> Decimal:
    if raw is None or not _AMOUNT_PATTERN.fullmatch(raw):
        raise ContractValidationError(
            f"{field} must be a plain non-negative decimal with at most four fractional digits"
        )
    try:
        value = Decimal(raw)
    except InvalidOperation as exception:
        raise ContractValidationError(f"{field} is not a valid decimal") from exception
    if strictly_positive and value <= ZERO:
        raise ContractValidationError(f"{field} must be strictly positive")
    return value.quantize(MONEY_SCALE)


def format_money(value: Decimal) -> str:
    return format(value.quantize(MONEY_SCALE), "f")


def validate_raw_row(row: Mapping[str, str], row_number: int = 1) -> RawTransaction:
    _require_exact_columns(tuple(row.keys()))
    prefix = f"row {row_number}: "
    profile_key = _require_pseudonymous_key(
        row["profile_key"], prefix + "invalid profile_key"
    )
    account_key = _require_pseudonymous_key(
        row["account_key"], prefix + "invalid account_key"
    )
    booking_date = _parse_date(row["booking_date"], prefix + "invalid booking_date")
    known_date = _parse_date(row["known_date"], prefix + "invalid known_date")
    amount = parse_money(row["amount"], prefix + "amount")
    direction = _require_member(row["direction"], _DIRECTIONS, prefix + "invalid direction")
    currency = _require_pattern(row["currency"], _CURRENCY_PATTERN, prefix + "invalid currency")
    source = _require_member(row["source"], _SOURCES, prefix + "invalid source")
    provider_status = _optional_pattern(
        row["provider_status"], _STATUS_PATTERN, prefix + "invalid provider_status"
    )
    is_internal_transfer = _parse_boolean(
        row["is_internal_transfer"], prefix + "invalid is_internal_transfer"
    )
    category_code = _optional_pattern(
        row["category_code"], _CATEGORY_PATTERN, prefix + "invalid category_code"
    )

    if source in {"MANUAL", "FILE_IMPORT"} and provider_status is not None:
        raise ContractValidationError(prefix + "manual and file-imported rows cannot have provider_status")
    if source == "BANK_SYNC" and provider_status is None:
        raise ContractValidationError(prefix + "bank-synchronized rows require provider_status")
    if known_date < booking_date:
        raise ContractValidationError(prefix + "known_date cannot precede booking_date")

    return RawTransaction(
        profile_key=profile_key,
        account_key=account_key,
        booking_date=booking_date,
        known_date=known_date,
        amount=amount,
        direction=direction,
        currency=currency,
        source=source,
        provider_status=provider_status,
        is_internal_transfer=is_internal_transfer,
        category_code=category_code,
    )


def read_raw_csv(path: str | Path) -> list[RawTransaction]:
    input_path = Path(path)
    with input_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise ContractValidationError("raw CSV must contain a header")
        _require_exact_columns(tuple(reader.fieldnames))
        return [validate_raw_row(row, index) for index, row in enumerate(reader, start=2)]


def _require_exact_columns(columns: tuple[str, ...]) -> None:
    if len(columns) != len(set(columns)):
        raise ContractValidationError("raw CSV contains duplicate columns")
    extras = sorted(set(columns) - set(RAW_COLUMNS))
    sensitive = [
        column
        for column in extras
        if any(fragment in column.lower() for fragment in _SENSITIVE_COLUMN_FRAGMENTS)
    ]
    if sensitive:
        raise ContractValidationError(
            "raw CSV contains forbidden sensitive columns: " + ", ".join(sensitive)
        )
    missing = sorted(set(RAW_COLUMNS) - set(columns))
    if extras or missing or columns != RAW_COLUMNS:
        details = []
        if missing:
            details.append("missing=" + ",".join(missing))
        if extras:
            details.append("unexpected=" + ",".join(extras))
        if not missing and not extras:
            details.append("columns must use the documented order")
        raise ContractValidationError("invalid raw CSV columns: " + "; ".join(details))


def _require_pattern(value: str, pattern: re.Pattern[str], message: str) -> str:
    if value is None or not pattern.fullmatch(value):
        raise ContractValidationError(message)
    return value


def _require_pseudonymous_key(value: str, message: str) -> str:
    key = _require_pattern(value, _KEY_PATTERN, message)
    if _UUID_PATTERN.fullmatch(key):
        raise ContractValidationError(message + ": database-style UUIDs are forbidden")
    return key


def _optional_pattern(value: str, pattern: re.Pattern[str], message: str) -> str | None:
    if value == "":
        return None
    return _require_pattern(value, pattern, message)


def _require_member(value: str, allowed: frozenset[str], message: str) -> str:
    if value not in allowed:
        raise ContractValidationError(message)
    return value


def _parse_date(value: str, message: str) -> date:
    try:
        parsed = date.fromisoformat(value)
    except (TypeError, ValueError) as exception:
        raise ContractValidationError(message) from exception
    if parsed.isoformat() != value:
        raise ContractValidationError(message)
    return parsed


def _parse_boolean(value: str, message: str) -> bool:
    if value == "true":
        return True
    if value == "false":
        return False
    raise ContractValidationError(message)
