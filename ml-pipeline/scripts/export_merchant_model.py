from __future__ import annotations

import argparse
import json
from pathlib import Path

from merchant_ml.artifact import train_and_export


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(train_and_export(args.output), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()

