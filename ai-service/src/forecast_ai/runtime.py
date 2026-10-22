"""Verified loading and inference for the trusted repository model."""

from __future__ import annotations

import hashlib
import json
import math
from decimal import Decimal, ROUND_HALF_EVEN
from pathlib import Path

import joblib
import numpy as np
from sklearn.pipeline import Pipeline

from forecast_ai.contracts import FEATURE_ORDER, FEATURE_VERSION, SUPPORTED_CURRENCY, PredictionRequest

MODEL_ID = "forecast-huber-synthetic-tnd"
MODEL_VERSION = "1.0.0"


class ModelUnavailableError(RuntimeError):
    pass


class ModelRuntime:
    def __init__(self, model_directory: str | Path) -> None:
        directory = Path(model_directory)
        self.artifact_path = directory / "forecast_huber_v1.joblib"
        self.metadata_path = directory / "forecast_huber_v1.metadata.json"
        self.checksum_path = directory / "forecast_huber_v1.sha256"
        self.pipeline: Pipeline | None = None
        self.metadata: dict[str, object] | None = None
        self.failure_reason: str | None = None

    @property
    def available(self) -> bool:
        return self.pipeline is not None and self.metadata is not None

    def load(self) -> None:
        self.pipeline = None
        self.metadata = None
        self.failure_reason = None
        try:
            metadata = json.loads(self.metadata_path.read_text(encoding="utf-8"))
            checksum = self.checksum_path.read_text(encoding="ascii").strip().split()
            if len(checksum) != 2 or checksum[1] != self.artifact_path.name:
                raise ValueError("invalid checksum sidecar")
            actual = _sha256(self.artifact_path)
            if checksum[0].lower() != actual or metadata.get("artifactSha256") != actual:
                raise ValueError("artifact hash verification failed")
            _validate_metadata(metadata)
            loaded = joblib.load(self.artifact_path)
            if not isinstance(loaded, Pipeline):
                raise ValueError("artifact is not the expected pipeline")
            self.pipeline = loaded
            self.metadata = metadata
        except Exception as exception:  # startup must report unavailable, not crash blindly
            self.failure_reason = type(exception).__name__

    def predict(self, request: PredictionRequest) -> Decimal:
        if not self.available:
            raise ModelUnavailableError("forecast model is unavailable")
        prediction = np.asarray(self.pipeline.predict(request.to_feature_frame()), dtype=float)
        if prediction.shape != (1,) or not math.isfinite(float(prediction[0])):
            raise ModelUnavailableError("forecast model returned an invalid prediction")
        return Decimal(str(float(prediction[0]))).quantize(
            Decimal("0.0001"), rounding=ROUND_HALF_EVEN
        )


def _validate_metadata(metadata: dict[str, object]) -> None:
    expected = {
        "modelId": MODEL_ID,
        "modelVersion": MODEL_VERSION,
        "featureContractVersion": FEATURE_VERSION,
        "currency": SUPPORTED_CURRENCY,
        "experimental": True,
        "syntheticTrainingData": True,
        "guaranteed": False,
    }
    for name, value in expected.items():
        if metadata.get(name) != value:
            raise ValueError(f"invalid model metadata field {name}")
    if tuple(metadata.get("featureOrder", ())) != FEATURE_ORDER:
        raise ValueError("invalid model feature order")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()
