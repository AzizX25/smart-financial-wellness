"""Export the approved synthetic-TND Huber model and its provenance sidecars."""

from __future__ import annotations

import argparse
from pathlib import Path

from forecast_ml.artifact import export_frozen_artifact


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--raw", type=Path, default=Path("data/raw/synthetic_transactions.csv")
    )
    parser.add_argument(
        "--processed",
        type=Path,
        default=Path("data/processed/forecast_residual_examples.csv"),
    )
    parser.add_argument(
        "--output", type=Path, default=Path("../ai-service/models/forecast")
    )
    arguments = parser.parse_args()
    exported = export_frozen_artifact(arguments.raw, arguments.processed, arguments.output)
    print(f"Exported {exported.artifact_path}")
    print(f"SHA-256 {exported.artifact_sha256}")


if __name__ == "__main__":
    main()
