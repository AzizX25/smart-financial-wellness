from __future__ import annotations

import shutil
from pathlib import Path

import numpy as np
from fastapi.testclient import TestClient

from forecast_ai.contracts import PredictionRequest
from forecast_ai.main import create_app
from forecast_ai.runtime import ModelRuntime

ROOT = Path(__file__).parents[1]
MODEL_DIR = ROOT / "models/forecast"


def valid_payload() -> dict[str, object]:
    return {
        "featureVersion": "forecast-residual-features-v1",
        "currency": "TND",
        "calendarMonth": "2026-08",
        "cutoffDay": 18,
        "daysRemaining": 13,
        "monthToDateCredit": "2500.0000",
        "monthToDateDebit": "900.0000",
        "monthToDateNetFlow": "1600.0000",
        "monthToDateTransactionCount": 12,
        "trailingSixMonthCredit": "15000.0000",
        "trailingSixMonthDebit": "10800.0000",
        "trailingSixMonthNetFlow": "4200.0000",
        "historicalRemainingNetFlowRange": "900.0000",
        "deterministicRemainingIncome": "800.0000",
        "deterministicRemainingExpense": "1200.0000",
        "deterministicRemainingNetFlow": "-400.0000",
        "usableHistoryCount": 6,
        "deterministicFallbackReason": "NONE",
    }


def client_for(directory: Path = MODEL_DIR) -> TestClient:
    return TestClient(create_app(ModelRuntime(directory)))


def test_valid_prediction_matches_loaded_pipeline_and_metadata() -> None:
    runtime = ModelRuntime(MODEL_DIR)
    with TestClient(create_app(runtime)) as client:
        response = client.post("/api/v1/forecast/predict-residual", json=valid_payload())
        model = client.get("/api/v1/forecast/model")
        expected = runtime.predict(PredictionRequest.model_validate(valid_payload()))

        assert response.status_code == 200
        assert np.isfinite(float(response.json()["predictedResidual"]))
        assert response.json()["predictedResidual"] == f"{expected:.4f}"
        assert response.json() | {"predictedResidual": "ignored"} == {
            "modelId": "forecast-huber-synthetic-tnd",
            "modelVersion": "1.0.0",
            "featureVersion": "forecast-residual-features-v1",
            "currency": "TND",
            "predictedResidual": "ignored",
            "experimental": True,
            "syntheticTrainingData": True,
            "guaranteed": False,
        }
        assert model.status_code == 200
        assert model.json()["experimental"] is True


def test_strict_schema_rejects_extra_pii_and_non_finite_values() -> None:
    with client_for() as client:
        pii = valid_payload() | {"userId": "secret"}
        non_finite = valid_payload() | {"monthToDateCredit": "NaN"}

        pii_response = client.post("/api/v1/forecast/predict-residual", json=pii)
        finite_response = client.post("/api/v1/forecast/predict-residual", json=non_finite)

        assert pii_response.status_code == 422
        assert pii_response.json()["fields"] == ["userId"]
        assert finite_response.status_code == 422
        assert "NaN" not in finite_response.text


def test_unsupported_currency_and_feature_version_are_rejected() -> None:
    with client_for() as client:
        currency = client.post(
            "/api/v1/forecast/predict-residual", json=valid_payload() | {"currency": "EUR"}
        )
        feature = client.post(
            "/api/v1/forecast/predict-residual",
            json=valid_payload() | {"featureVersion": "forecast-residual-features-v2"},
        )

        assert currency.status_code == 422
        assert feature.status_code == 422


def test_artifact_hash_failure_marks_health_and_prediction_unavailable(tmp_path: Path) -> None:
    copied = tmp_path / "model"
    shutil.copytree(MODEL_DIR, copied)
    artifact = copied / "forecast_huber_v1.joblib"
    artifact.write_bytes(artifact.read_bytes() + b"tampered")

    with client_for(copied) as client:
        health = client.get("/health")
        prediction = client.post("/api/v1/forecast/predict-residual", json=valid_payload())

        assert health.status_code == 503
        assert health.json() == {"status": "UNAVAILABLE", "modelAvailable": False}
        assert prediction.status_code == 503
        assert prediction.json()["code"] == "MODEL_UNAVAILABLE"
