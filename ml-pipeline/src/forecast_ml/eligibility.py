"""Transaction eligibility shared by dataset features, baseline, and targets."""

from __future__ import annotations

from collections.abc import Iterable
from enum import Enum

from forecast_ml.schemas import RawTransaction


class ExclusionReason(str, Enum):
    ELIGIBLE = "ELIGIBLE"
    INTERNAL_TRANSFER = "INTERNAL_TRANSFER"
    BANK_NOT_POSTED = "BANK_NOT_POSTED"


def exclusion_reason(transaction: RawTransaction) -> ExclusionReason:
    if transaction.is_internal_transfer:
        return ExclusionReason.INTERNAL_TRANSFER
    if transaction.source == "BANK_SYNC" and transaction.provider_status != "POSTED":
        return ExclusionReason.BANK_NOT_POSTED
    return ExclusionReason.ELIGIBLE


def is_cash_flow_eligible(transaction: RawTransaction) -> bool:
    return exclusion_reason(transaction) == ExclusionReason.ELIGIBLE


def eligible_transactions(transactions: Iterable[RawTransaction]) -> list[RawTransaction]:
    return [transaction for transaction in transactions if is_cash_flow_eligible(transaction)]
