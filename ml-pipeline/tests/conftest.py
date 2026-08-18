from __future__ import annotations

import calendar
from collections.abc import Callable

import pandas as pd
import pytest

from forecast_ml.schemas import PROCESSED_COLUMNS


@pytest.fixture
def processed_frame_factory() -> Callable[..., pd.DataFrame]:
    def build(
        *,
        currencies: tuple[str, ...] = ("TND",),
        profiles: tuple[str, ...] = ("profile_0001", "profile_0002"),
        cutoffs: tuple[int, ...] = (5, 25),
    ) -> pd.DataFrame:
        records: list[dict[str, str]] = []
        months = pd.period_range("2024-01", "2025-12", freq="M")
        for currency in currencies:
            for profile_index, profile in enumerate(profiles, start=1):
                for month_index, month in enumerate(months, start=1):
                    fallback = (
                        "NO_USABLE_HISTORY"
                        if month_index == 1
                        else "LIMITED_HISTORY"
                        if month_index <= 3
                        else "NONE"
                    )
                    usable = min(max(month_index - 1, 0), 6)
                    for cutoff in cutoffs:
                        month_end = calendar.monthrange(month.year, month.month)[1]
                        actual = 100 + month_index * 2 + profile_index - cutoff
                        deterministic = 90 + month_index + profile_index - cutoff
                        residual = actual - deterministic
                        record = {
                            "dataset_version": "forecast-residual-v1",
                            "profile_key": profile,
                            "currency": currency,
                            "target_month": str(month),
                            "cutoff_date": f"{month}-{cutoff:02d}",
                            "calendar_month": str(month.month),
                            "cutoff_day": str(cutoff),
                            "days_remaining": str(month_end - cutoff),
                            "month_to_date_credit": f"{200 + month_index:.4f}",
                            "month_to_date_debit": f"{80 + cutoff:.4f}",
                            "month_to_date_net_flow": f"{120 + month_index - cutoff:.4f}",
                            "month_to_date_transaction_count": str(2 + cutoff // 5),
                            "trailing_six_month_credit": f"{900 + month_index:.4f}",
                            "trailing_six_month_debit": f"{500 + month_index:.4f}",
                            "trailing_six_month_net_flow": "400.0000",
                            "historical_remaining_net_flow_range": f"{20 + month_index:.4f}",
                            "deterministic_remaining_income": f"{150 + month_index:.4f}",
                            "deterministic_remaining_expense": f"{60 + cutoff:.4f}",
                            "deterministic_remaining_net_flow": f"{deterministic:.4f}",
                            "usable_history_count": str(usable),
                            "deterministic_fallback_reason": fallback,
                            "actual_remaining_income": f"{170 + month_index:.4f}",
                            "actual_remaining_expense": f"{170 + month_index - actual:.4f}",
                            "actual_remaining_net_flow": f"{actual:.4f}",
                            "residual_target": f"{residual:.4f}",
                        }
                        records.append(record)
        return pd.DataFrame(records, columns=PROCESSED_COLUMNS)

    return build
