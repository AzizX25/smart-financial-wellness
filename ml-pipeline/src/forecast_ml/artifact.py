"""Export and verify the single frozen Forecast prototype artifact."""

from __future__ import annotations

import hashlib
import json
import platform
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Final

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.pipeline import Pipeline

from forecast_ml.feature_matrix import (
    FEATURE_COLUMNS,
    FEATURE_CONTRACT_VERSION,
    build_experiment_matrix,
    combine_experiment_matrices,
    load_processed_dataset,
)
from forecast_ml.modeling import HUBER_MODEL, create_candidate_pipeline
from forecast_ml.temporal_split import TemporalBoundaries, chronological_split

MODEL_ID: Final = "forecast-huber-synthetic-tnd"
MODEL_VERSION: Final = "1.0.0"
MODEL_FILENAME: Final = "forecast_huber_v1.joblib"
METADATA_FILENAME: Final = "forecast_huber_v1.metadata.json"
CHECKSUM_FILENAME: Final = "forecast_huber_v1.sha256"
CURRENCY: Final = "TND"
RANDOM_SEED: Final = 20260818
GENERATOR_SEED: Final = 20260816
RAW_DATASET_SHA256: Final = "943a56e8f9ce8de7059fef336c818f23dae7f6845655df5aa437556bfd1b8139"
PROCESSED_DATASET_SHA256: Final = "763e102ae5b7bc71a6add3603fd5d7a99b231969c9bec2fb17e7451065cf2dbc"
DATASET_VERSION: Final = "forecast-residual-v1"

FROZEN_VALIDATION_METRICS: Final = {
    "rowCount": 75,
    "deterministicMae": 467.654909,
    "mae": 438.304734,
    "rmse": 632.494369,
    "medianAbsoluteError": 247.072835,
    "signedMeanError": -33.970681,
    "improvementPercentage": 6.276033,
}
FROZEN_TEST_METRICS: Final = {
    "rowCount": 75,
    "deterministicMae": 344.948136,
    "mae": 328.065829,
    "rmse": 467.627051,
    "medianAbsoluteError": 186.662423,
    "signedMeanError": -168.558884,
    "improvementPercentage": 4.894158,
    "improvementByCutoff": {
        "5": 3.475886,
        "10": 5.824223,
        "15": 7.283011,
        "20": 11.480422,
        "25": -19.620741,
    },
}


@dataclass(frozen=True, slots=True)
class ArtifactExport:
    artifact_path: Path
    metadata_path: Path
    checksum_path: Path
    artifact_sha256: str
    parity_predictions: tuple[float, ...]


def export_frozen_artifact(
    raw_dataset_path: str | Path,
    processed_dataset_path: str | Path,
    output_directory: str | Path,
    *,
    created_at: datetime | None = None,
) -> ArtifactExport:
    """Fit Huber on training+validation only and export the approved artifact."""
    raw_path = Path(raw_dataset_path)
    processed_path = Path(processed_dataset_path)
    _require_hash(raw_path, RAW_DATASET_SHA256, "raw dataset")
    _require_hash(processed_path, PROCESSED_DATASET_SHA256, "processed dataset")

    processed = load_processed_dataset(processed_path)
    versions = set(processed["dataset_version"])
    if versions != {DATASET_VERSION}:
        raise ValueError(f"unexpected dataset versions: {sorted(versions)}")

    matrix = build_experiment_matrix(processed, currency=CURRENCY)
    partitions = chronological_split(matrix, TemporalBoundaries())
    development = combine_experiment_matrices(partitions.training, partitions.validation)
    if development.context["target_month"].max() >= partitions.test_period_start:
        raise RuntimeError("test rows entered the final fitting matrix")

    pipeline = create_candidate_pipeline(HUBER_MODEL, random_seed=RANDOM_SEED)
    pipeline.fit(development.features, development.target)
    fixtures = _parity_fixtures(development.features)
    in_memory_predictions = _finite_predictions(pipeline, fixtures)

    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    artifact_path = output / MODEL_FILENAME
    metadata_path = output / METADATA_FILENAME
    checksum_path = output / CHECKSUM_FILENAME
    joblib.dump(pipeline, artifact_path, compress=3)
    artifact_hash = sha256_file(artifact_path)

    timestamp = created_at or datetime.now(timezone.utc)
    if timestamp.tzinfo is None:
        raise ValueError("created_at must include a timezone")
    metadata = _metadata(artifact_hash, timestamp.astimezone(timezone.utc), development)
    metadata_path.write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    checksum_path.write_text(f"{artifact_hash}  {MODEL_FILENAME}\n", encoding="ascii")

    reloaded, reloaded_metadata = load_verified_artifact(
        artifact_path, metadata_path, checksum_path
    )
    if tuple(reloaded_metadata["featureOrder"]) != FEATURE_COLUMNS:
        raise RuntimeError("exported metadata feature order changed")
    reloaded_predictions = _finite_predictions(reloaded, fixtures)
    np.testing.assert_allclose(reloaded_predictions, in_memory_predictions, rtol=0, atol=0)
    return ArtifactExport(
        artifact_path,
        metadata_path,
        checksum_path,
        artifact_hash,
        tuple(float(value) for value in reloaded_predictions),
    )


def load_verified_artifact(
    artifact_path: str | Path,
    metadata_path: str | Path,
    checksum_path: str | Path,
) -> tuple[Pipeline, dict[str, object]]:
    """Verify trusted sidecar hashes before deserializing the repository artifact."""
    artifact = Path(artifact_path)
    metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
    checksum_parts = Path(checksum_path).read_text(encoding="ascii").strip().split()
    if len(checksum_parts) != 2 or checksum_parts[1] != artifact.name:
        raise ValueError("invalid artifact checksum file")
    expected_hash = checksum_parts[0].lower()
    actual_hash = sha256_file(artifact)
    if actual_hash != expected_hash or metadata.get("artifactSha256") != actual_hash:
        raise ValueError("artifact SHA-256 verification failed")
    if metadata.get("modelId") != MODEL_ID or metadata.get("modelVersion") != MODEL_VERSION:
        raise ValueError("artifact metadata identifies an unsupported model")
    if metadata.get("featureContractVersion") != FEATURE_CONTRACT_VERSION:
        raise ValueError("artifact metadata identifies an unsupported feature contract")
    model = joblib.load(artifact)
    if not isinstance(model, Pipeline):
        raise ValueError("artifact does not contain the expected pipeline")
    return model, metadata


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _require_hash(path: Path, expected: str, label: str) -> None:
    actual = sha256_file(path)
    if actual != expected:
        raise ValueError(f"{label} SHA-256 mismatch: expected {expected}, got {actual}")


def _parity_fixtures(features: pd.DataFrame) -> pd.DataFrame:
    positions = [0, len(features) // 2, len(features) - 1]
    return features.iloc[positions].reset_index(drop=True)


def _finite_predictions(pipeline: Pipeline, features: pd.DataFrame) -> np.ndarray:
    predictions = np.asarray(pipeline.predict(features), dtype=float)
    if predictions.shape != (len(features),) or not np.isfinite(predictions).all():
        raise ValueError("artifact produced invalid predictions")
    return predictions


def _metadata(
    artifact_hash: str,
    timestamp: datetime,
    development,
) -> dict[str, object]:
    fitted_months = development.context["target_month"]
    return {
        "modelId": MODEL_ID,
        "modelVersion": MODEL_VERSION,
        "modelType": "HuberRegressor",
        "featureContractVersion": FEATURE_CONTRACT_VERSION,
        "featureOrder": list(FEATURE_COLUMNS),
        "datasetVersion": DATASET_VERSION,
        "currency": CURRENCY,
        "experimental": True,
        "syntheticTrainingData": True,
        "guaranteed": False,
        "hyperparameters": {
            "epsilon": 1.35,
            "alpha": 0.0001,
            "maxIter": 1000,
            "tol": 1e-5,
        },
        "trainingPeriod": {"start": "2024-01", "end": "2025-06"},
        "validationPeriodIncludedInFinalFit": {"start": "2025-07", "end": "2025-09"},
        "excludedTestPeriod": {"start": "2025-10", "end": "2025-12"},
        "fittedPeriod": {
            "start": str(fitted_months.min()),
            "end": str(fitted_months.max()),
            "rowCount": len(development.features),
        },
        "randomSeed": RANDOM_SEED,
        "generator": {
            "seed": GENERATOR_SEED,
            "profiles": 5,
            "startMonth": "2024-01",
            "endMonth": "2025-12",
            "currency": CURRENCY,
            "asOfDate": "2026-01-01",
            "cutoffDays": [5, 10, 15, 20, 25],
        },
        "rawDatasetSha256": RAW_DATASET_SHA256,
        "processedDatasetSha256": PROCESSED_DATASET_SHA256,
        "artifactSha256": artifact_hash,
        "pythonVersion": platform.python_version(),
        "dependencies": {
            "joblib": joblib.__version__,
            "numpy": np.__version__,
            "pandas": pd.__version__,
            "scikitLearn": sklearn.__version__,
        },
        "frozenValidationMetrics": FROZEN_VALIDATION_METRICS,
        "frozenTestMetrics": FROZEN_TEST_METRICS,
        "createdAtUtc": timestamp.isoformat().replace("+00:00", "Z"),
    }
