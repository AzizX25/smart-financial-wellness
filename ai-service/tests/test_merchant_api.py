from __future__ import annotations

import shutil
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from forecast_ai.main import create_app
from forecast_ai.merchant_contracts import MerchantPrediction
from forecast_ai.merchant_runtime import MerchantRuntime
from forecast_ai.runtime import ModelRuntime

ROOT = Path(__file__).parents[1]
CANONICAL_REQUEST = {
    "items": [{
        "requestKey": "merchant-1",
        "sanitizedMerchantText": "carrefour la marsa",
        "direction": "DEBIT",
        "currency": "TND",
        "featureVersion": "merchant-recognition-features-v1",
    }],
}


def item(text: str, **overrides) -> dict[str, object]:
    return {
        "requestKey": "request-1", "sanitizedMerchantText": text, "direction": "DEBIT",
        "currency": "TND", "featureVersion": "merchant-recognition-features-v1", **overrides,
    }


def client(merchant_directory: Path | None = None) -> TestClient:
    return TestClient(create_app(
        ModelRuntime(ROOT / "models/forecast"),
        MerchantRuntime(merchant_directory or ROOT / "models/merchant"),
    ))


def test_exact_fuzzy_ml_unknown_and_unsupported_currency() -> None:
    with client() as api:
        payload = {"items": [
            item("carrefour"),
            item("carrefur", requestKey="request-2"),
            item("commande a coursera tunisie", requestKey="request-3"),
            item("retrait dab", requestKey="request-4"),
            item("carrefour", requestKey="request-5", currency="EUR"),
        ]}
        response = api.post("/api/v1/merchant-recognition/predict", json=payload)
        model = api.get("/api/v1/merchant-recognition/model")

        assert response.status_code == 200
        results = response.json()["items"]
        assert results[0]["matchMethod"] == "EXACT_ALIAS" and results[0]["categoryCode"] == "FOOD"
        assert results[1]["matchMethod"] == "FUZZY_ALIAS"
        assert results[2]["status"] == "RECOGNIZED"
        assert results[3]["status"] == "UNRECOGNIZED"
        assert results[4]["status"] == "UNRECOGNIZED"
        assert all(result["candidateCategoryCodes"] == [] for result in results[:3])
        assert results[3]["candidateCategoryCodes"] == []
        assert results[4]["candidateCategoryCodes"] == []
        assert all(result["guaranteed"] is False for result in results)
        assert model.status_code == 200 and model.json()["syntheticTrainingData"] is True


def test_accepts_the_canonical_java_request_field_names_and_enum_casing() -> None:
    assert set(CANONICAL_REQUEST) == {"items"}
    assert set(CANONICAL_REQUEST["items"][0]) == {
        "requestKey", "sanitizedMerchantText", "direction", "currency", "featureVersion",
    }
    assert CANONICAL_REQUEST["items"][0]["direction"] == "DEBIT"
    assert CANONICAL_REQUEST["items"][0]["currency"] == "TND"
    with client() as api:
        assert api.post(
            "/api/v1/merchant-recognition/predict", json=CANONICAL_REQUEST,
        ).status_code == 200


def test_strict_fields_pii_uuid_and_batch_limit_are_rejected() -> None:
    with client() as api:
        extra = api.post("/api/v1/merchant-recognition/predict", json={"items": [item("carrefour", amount="20.00")]})
        root_extra = api.post(
            "/api/v1/merchant-recognition/predict",
            json={**CANONICAL_REQUEST, "userId": "not-allowed"},
        )
        wrong_case = api.post(
            "/api/v1/merchant-recognition/predict",
            json={"items": [item("carrefour", direction="debit", currency="tnd")]},
        )
        pii = api.post("/api/v1/merchant-recognition/predict", json={"items": [item("john@example.com")]})
        uuid_key = api.post("/api/v1/merchant-recognition/predict", json={"items": [item("carrefour", requestKey="11111111-1111-1111-1111-111111111111")]})
        oversized = api.post("/api/v1/merchant-recognition/predict", json={"items": [item("carrefour", requestKey=f"key-{index}") for index in range(51)]})
        assert extra.status_code == root_extra.status_code == wrong_case.status_code == 422
        assert pii.status_code == uuid_key.status_code == oversized.status_code == 422


def test_invalid_batch_is_rejected_before_any_inference() -> None:
    runtime = MerchantRuntime(ROOT / "models/merchant")
    application = create_app(ModelRuntime(ROOT / "models/forecast"), runtime)
    with patch.object(runtime, "predict", wraps=runtime.predict) as predict:
        with TestClient(application) as api:
            response = api.post(
                "/api/v1/merchant-recognition/predict",
                json={"items": [item("carrefour"), item("steg", amount="10.00")]},
            )
        assert response.status_code == 422
        predict.assert_not_called()


def test_artifact_failure_isolated_to_merchant_endpoint(tmp_path: Path) -> None:
    copied = tmp_path / "merchant"
    shutil.copytree(ROOT / "models/merchant", copied)
    artifact = copied / "merchant_recognition_v1.joblib"
    artifact.write_bytes(artifact.read_bytes() + b"tampered")
    with client(copied) as api:
        assert api.get("/api/v1/merchant-recognition/model").status_code == 503
        assert api.post("/api/v1/merchant-recognition/predict", json={"items": [item("carrefour")]}).status_code == 503
        assert api.get("/api/v1/forecast/model").status_code == 200


def test_candidate_contract_rejects_duplicates_foreign_codes_and_recognized_candidates() -> None:
    valid = {
        "requestKey": "request-1", "status": "UNRECOGNIZED",
        "canonicalMerchant": None, "categoryCode": None, "matchMethod": None,
        "matchScore": None, "modelId": "merchant-recognition-synthetic-v1",
        "modelVersion": "1.0.0", "featureVersion": "merchant-recognition-features-v1",
        "experimental": True, "syntheticTrainingData": True, "guaranteed": False,
        "fallbackReason": "MODEL_ABSTAINED", "candidateCategoryCodes": ["FOOD", "SHOPPING"],
    }
    assert MerchantPrediction.model_validate(valid).candidate_category_codes == ["FOOD", "SHOPPING"]
    for candidates in (["FOOD", "FOOD"], ["FOOD", "INCOME_SALARY"]):
        with pytest.raises(ValidationError):
            MerchantPrediction.model_validate({**valid, "candidateCategoryCodes": candidates})
    with pytest.raises(ValidationError):
        MerchantPrediction.model_validate({
            **valid,
            "status": "RECOGNIZED",
            "canonicalMerchant": "Commerce",
            "categoryCode": "FOOD",
            "matchMethod": "ML",
            "matchScore": 0.9,
        })
