"""Strict HTTP and feature contracts for Forecast residual inference."""

from __future__ import annotations

import calendar
import math
import re
from decimal import Decimal, InvalidOperation
from typing import Literal

import pandas as pd
from pydantic import BaseModel, ConfigDict, Field, model_validator

FEATURE_VERSION = "forecast-residual-features-v1"
SUPPORTED_CURRENCY = "TND"
FEATURE_ORDER = (
    "calendar_month_sin",
    "calendar_month_cos",
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
)
_MONTH = re.compile(r"^(?P<year>[0-9]{4})-(?P<month>0[1-9]|1[0-2])$")
_MONEY = re.compile(r"^-?(?:0|[1-9][0-9]{0,14})(?:\.[0-9]{1,4})?$")


def _camel(name: str) -> str:
    first, *rest = name.split("_")
    return first + "".join(part.title() for part in rest)


class StrictContract(BaseModel):
    model_config = ConfigDict(
        alias_generator=_camel,
        populate_by_name=True,
        extra="forbid",
        strict=True,
    )


class PredictionRequest(StrictContract):
    feature_version: str
    currency: str
    calendar_month: str
    cutoff_day: int = Field(ge=1, le=31)
    days_remaining: int = Field(ge=0, le=30)
    month_to_date_credit: str
    month_to_date_debit: str
    month_to_date_net_flow: str
    month_to_date_transaction_count: int = Field(ge=0)
    trailing_six_month_credit: str
    trailing_six_month_debit: str
    trailing_six_month_net_flow: str
    historical_remaining_net_flow_range: str
    deterministic_remaining_income: str
    deterministic_remaining_expense: str
    deterministic_remaining_net_flow: str
    usable_history_count: int = Field(ge=0, le=6)
    deterministic_fallback_reason: Literal[
        "NONE", "LIMITED_HISTORY", "NO_USABLE_HISTORY"
    ]

    @model_validator(mode="after")
    def validate_contract(self) -> "PredictionRequest":
        if self.feature_version != FEATURE_VERSION:
            raise ValueError("unsupported feature version")
        if self.currency != SUPPORTED_CURRENCY:
            raise ValueError("unsupported currency")
        match = _MONTH.fullmatch(self.calendar_month)
        if not match:
            raise ValueError("calendarMonth must use YYYY-MM")
        last_day = calendar.monthrange(int(match.group("year")), int(match.group("month")))[1]
        if self.cutoff_day > last_day:
            raise ValueError("cutoffDay does not exist in calendarMonth")
        if self.days_remaining != last_day - self.cutoff_day:
            raise ValueError("daysRemaining does not match calendarMonth and cutoffDay")

        values = {name: _decimal(getattr(self, name), name) for name in _MONEY_FIELDS}
        for name in _NON_NEGATIVE_MONEY_FIELDS:
            if values[name] < 0:
                raise ValueError(f"{_camel(name)} must be non-negative")
        _require_equal(
            values["month_to_date_net_flow"],
            values["month_to_date_credit"] - values["month_to_date_debit"],
            "monthToDateNetFlow",
        )
        _require_equal(
            values["trailing_six_month_net_flow"],
            values["trailing_six_month_credit"] - values["trailing_six_month_debit"],
            "trailingSixMonthNetFlow",
        )
        _require_equal(
            values["deterministic_remaining_net_flow"],
            values["deterministic_remaining_income"]
            - values["deterministic_remaining_expense"],
            "deterministicRemainingNetFlow",
        )
        return self

    def to_feature_frame(self) -> pd.DataFrame:
        month_number = int(self.calendar_month[-2:])
        radians = 2.0 * math.pi * (month_number - 1.0) / 12.0
        decimals = {name: float(_decimal(getattr(self, name), name)) for name in _MONEY_FIELDS}
        return pd.DataFrame(
            [{
                "calendar_month_sin": math.sin(radians),
                "calendar_month_cos": math.cos(radians),
                "cutoff_day": float(self.cutoff_day),
                "days_remaining": float(self.days_remaining),
                "month_to_date_credit": decimals["month_to_date_credit"],
                "month_to_date_debit": decimals["month_to_date_debit"],
                "month_to_date_net_flow": decimals["month_to_date_net_flow"],
                "month_to_date_transaction_count": float(self.month_to_date_transaction_count),
                "trailing_six_month_credit": decimals["trailing_six_month_credit"],
                "trailing_six_month_debit": decimals["trailing_six_month_debit"],
                "trailing_six_month_net_flow": decimals["trailing_six_month_net_flow"],
                "historical_remaining_net_flow_range": decimals[
                    "historical_remaining_net_flow_range"
                ],
                "deterministic_remaining_income": decimals["deterministic_remaining_income"],
                "deterministic_remaining_expense": decimals["deterministic_remaining_expense"],
                "deterministic_remaining_net_flow": decimals[
                    "deterministic_remaining_net_flow"
                ],
                "usable_history_count": float(self.usable_history_count),
                "deterministic_fallback_reason": self.deterministic_fallback_reason,
            }],
            columns=FEATURE_ORDER,
        )


class PredictionResponse(StrictContract):
    model_id: str
    model_version: str
    feature_version: str
    currency: str
    predicted_residual: str
    experimental: bool
    synthetic_training_data: bool
    guaranteed: bool


class ModelResponse(StrictContract):
    model_id: str
    model_version: str
    feature_version: str
    currency: str
    experimental: bool
    synthetic_training_data: bool
    guaranteed: bool


_MONEY_FIELDS = (
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
)
_NON_NEGATIVE_MONEY_FIELDS = (
    "month_to_date_credit",
    "month_to_date_debit",
    "trailing_six_month_credit",
    "trailing_six_month_debit",
    "historical_remaining_net_flow_range",
    "deterministic_remaining_income",
    "deterministic_remaining_expense",
)


def _decimal(raw: str, name: str) -> Decimal:
    if not _MONEY.fullmatch(raw):
        raise ValueError(f"{_camel(name)} must be a decimal string with at most four decimals")
    try:
        value = Decimal(raw)
    except InvalidOperation as exception:
        raise ValueError(f"{_camel(name)} must be a finite decimal") from exception
    if not value.is_finite():
        raise ValueError(f"{_camel(name)} must be a finite decimal")
    return value


def _require_equal(actual: Decimal, expected: Decimal, label: str) -> None:
    if actual != expected:
        raise ValueError(f"{label} is inconsistent with its components")
