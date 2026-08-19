from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pytest

from forecast_ml.artifact import (
    CHECKSUM_FILENAME,
    DATASET_VERSION,
    FEATURE_CONTRACT_VERSION,
    METADATA_FILENAME,
    MODEL_FILENAME,
    PROCESSED_DATASET_SHA256,
    RAW_DATASET_SHA256,
    export_frozen_artifact,
    load_verified_artifact,
)

PROJECT = Path(__file__).parents[1]
RAW = PROJECT / "data/raw/synthetic_transactions.csv"
PROCESSED = PROJECT / "data/processed/forecast_residual_examples.csv"


def test_export_records_provenance_excludes_test_and_reloads_with_exact_parity(tmp_path: Path) -> None:
    exported = export_frozen_artifact(
        RAW,
        PROCESSED,
        tmp_path,
        created_at=datetime(2026, 8, 18, tzinfo=timezone.utc),
    )
    metadata = json.loads(exported.metadata_path.read_text(encoding="utf-8"))

    assert metadata["datasetVersion"] == DATASET_VERSION
    assert metadata["featureContractVersion"] == FEATURE_CONTRACT_VERSION
    assert metadata["rawDatasetSha256"] == RAW_DATASET_SHA256
    assert metadata["processedDatasetSha256"] == PROCESSED_DATASET_SHA256
    assert metadata["fittedPeriod"] == {
        "start": "2024-01", "end": "2025-09", "rowCount": 525
    }
    assert metadata["excludedTestPeriod"] == {"start": "2025-10", "end": "2025-12"}
    assert metadata["experimental"] is True
    assert metadata["syntheticTrainingData"] is True
    assert metadata["guaranteed"] is False
    assert np.isfinite(exported.parity_predictions).all()


def test_repeated_exports_produce_functionally_identical_predictions(tmp_path: Path) -> None:
    first = export_frozen_artifact(RAW, PROCESSED, tmp_path / "first")
    second = export_frozen_artifact(RAW, PROCESSED, tmp_path / "second")

    np.testing.assert_allclose(first.parity_predictions, second.parity_predictions, rtol=0, atol=0)


def test_hash_is_verified_before_deserialization(tmp_path: Path) -> None:
    exported = export_frozen_artifact(RAW, PROCESSED, tmp_path)
    exported.artifact_path.write_bytes(exported.artifact_path.read_bytes() + b"tampered")

    with pytest.raises(ValueError, match="SHA-256 verification failed"):
        load_verified_artifact(
            tmp_path / MODEL_FILENAME,
            tmp_path / METADATA_FILENAME,
            tmp_path / CHECKSUM_FILENAME,
        )
