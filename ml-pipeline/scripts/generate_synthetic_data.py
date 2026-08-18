"""Generate a deterministic pseudonymous raw transaction CSV."""

from __future__ import annotations

import argparse
from datetime import date
from pathlib import Path

from forecast_ml.synthetic_data import (
    SyntheticDataConfig,
    generate_transactions,
    write_raw_csv,
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("data/raw/synthetic_transactions.csv"))
    parser.add_argument("--seed", type=int, default=20260816)
    parser.add_argument("--profiles", type=int, default=5)
    parser.add_argument("--start-month", type=_parse_month, default=date(2024, 1, 1))
    parser.add_argument("--end-month", type=_parse_month, default=date(2025, 12, 1))
    parser.add_argument("--currency", default="TND")
    arguments = parser.parse_args()

    config = SyntheticDataConfig(
        seed=arguments.seed,
        profiles=arguments.profiles,
        start_month=arguments.start_month,
        end_month=arguments.end_month,
        currency=arguments.currency,
    )
    transactions = generate_transactions(config)
    write_raw_csv(transactions, arguments.output)
    print(f"Generated {len(transactions)} pseudonymous rows at {arguments.output}")


def _parse_month(raw: str) -> date:
    try:
        parsed = date.fromisoformat(raw + "-01")
    except ValueError as exception:
        raise argparse.ArgumentTypeError("months must use YYYY-MM") from exception
    if f"{parsed.year:04d}-{parsed.month:02d}" != raw:
        raise argparse.ArgumentTypeError("months must use YYYY-MM")
    return parsed


if __name__ == "__main__":
    main()
