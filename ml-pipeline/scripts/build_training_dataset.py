"""Validate raw transactions and build the stable residual-target CSV."""

from __future__ import annotations

import argparse
from datetime import date
from pathlib import Path

from forecast_ml.dataset_builder import build_training_dataset, write_processed_csv
from forecast_ml.validation import read_raw_csv


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--as-of", type=date.fromisoformat, required=True)
    parser.add_argument("--cutoffs", type=_parse_cutoffs, default=(5, 10, 15, 20, 25))
    arguments = parser.parse_args()

    transactions = read_raw_csv(arguments.input)
    examples = build_training_dataset(
        transactions,
        as_of_date=arguments.as_of,
        cutoff_days=arguments.cutoffs,
    )
    write_processed_csv(examples, arguments.output)
    print(f"Built {len(examples)} leakage-safe examples at {arguments.output}")


def _parse_cutoffs(raw: str) -> tuple[int, ...]:
    try:
        return tuple(int(item) for item in raw.split(",") if item)
    except ValueError as exception:
        raise argparse.ArgumentTypeError("cutoffs must be comma-separated integers") from exception


if __name__ == "__main__":
    main()
