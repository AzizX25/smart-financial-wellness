from __future__ import annotations

from pathlib import Path

import joblib
import pytest

from merchant_ml.artifact import build_dataset, load_verified_artifact, train_and_export
from merchant_ml.normalization import normalize_merchant_text


def test_normalization_is_deterministic() -> None:
    assert normalize_merchant_text("  Géant—TUNISIE!! ") == "geant tunisie"
    assert normalize_merchant_text("H&M") == "h and m"


def test_generation_is_deterministic_and_split_isolated() -> None:
    first = build_dataset()
    second = build_dataset()
    assert first.equals(second)
    assert first["text"].is_unique
    splits = {name: set(group["text"]) for name, group in first.groupby("split")}
    assert splits["train"].isdisjoint(splits["validation"] | splits["test"])


def test_export_integrity_unknown_rejection_and_prediction_parity(tmp_path: Path) -> None:
    metadata = train_and_export(tmp_path)
    artifact = load_verified_artifact(tmp_path)
    pipeline = artifact["pipeline"]
    assert metadata["experimental"] is True
    assert metadata["syntheticTrainingData"] is True
    assert metadata["guaranteed"] is False
    assert metadata["testMetrics"]["unknownFalseAcceptanceRate"] <= 0.10
    before = pipeline.predict_proba(["paiement carrefour"])
    after = joblib.load(tmp_path / "merchant_recognition_v1.joblib")["pipeline"].predict_proba(["paiement carrefour"])
    assert before.tolist() == after.tolist()

    artifact_path = tmp_path / "merchant_recognition_v1.joblib"
    artifact_path.write_bytes(artifact_path.read_bytes() + b"tampered")
    with pytest.raises(ValueError, match="SHA-256"):
        load_verified_artifact(tmp_path)
