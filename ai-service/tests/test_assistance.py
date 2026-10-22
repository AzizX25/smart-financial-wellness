import base64
import json
from io import BytesIO

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from pydantic import ValidationError

from forecast_ai.assistance import (
    VERSION, ExplanationRequest, LocalAssistance, PrintedReceiptDecision, ReceiptDecision, ReceiptRequest, sanitize_image,
)
from forecast_ai.main import create_app


def image(width=20, height=20):
    output = BytesIO()
    Image.new("RGB", (width, height), "white").save(output, format="PNG")
    return base64.b64encode(output.getvalue()).decode("ascii")


def explanation():
    return ExplanationRequest(contractVersion=VERSION, ruleCode="SAVINGS_MARGIN", factCode="SAVINGS_RATE")


def printed(**changes):
    return json.dumps({"merchant": "Synthetic café", "dateText": "01/10/2026", "totalText": "12,3456",
                       "totalLabel": "TOTAL", "currency": "TND", **changes})


def fake_chat(monkeypatch, content):
    calls = []

    def fake(*args, **kwargs):
        calls.append((args, kwargs))
        return {"message": {"content": content, "thinking": "discarded synthetic thinking"}}

    monkeypatch.setattr("forecast_ai.assistance._post_json", fake)
    return calls


def test_explanation_uses_only_approved_fact_codes_and_strict_schema(monkeypatch):
    calls = fake_chat(monkeypatch, json.dumps({"text": "Comparez les dépenses et les revenus avant d’ajuster le budget.", "factCodes": ["SAVINGS_RATE"]}))
    result = LocalAssistance(explainer_enabled=True).explain(explanation())
    assert result.status == "EXPLAINED"
    assert "thinking" not in result.model_dump_json()
    url, payload, headers, timeout = calls[0][0]
    assert url == "http://localhost:11434/api/chat" and not headers
    assert payload["think"] is False and payload["stream"] is False
    assert payload["format"]["additionalProperties"] is False
    assert set(payload["format"]["required"]) == {"text", "factCodes"}
    assert calls[0][1] == {"retries": 0} and timeout == 30
    assert set(json.loads(payload["messages"][1]["content"])) == {"factCode", "finding"}


@pytest.mark.parametrize("content", [
    'Here is JSON: {"text":"Bonjour","factCodes":["SAVINGS_RATE"]}',
    '<think>reasoning</think>{"text":"Bonjour","factCodes":["SAVINGS_RATE"]}',
    '{"text":"Bonjour","factCodes":["SAVINGS_RATE"],"amount":"50"}',
    '{"text":"Épargnez 500 TND","factCodes":["SAVINGS_RATE"]}',
    '{"text":"Un rendement garanti","factCodes":["SAVINGS_RATE"]}',
    '{"text":"Bonjour","factCodes":["FOREIGN"]}',
    '{"text":"Bonjour","factCodes":["SAVINGS_RATE","SAVINGS_RATE"]}',
    '{"text":"Bonjour"}',
])
def test_invalid_explanations_fall_back_without_exposing_output(monkeypatch, content):
    fake_chat(monkeypatch, content)
    result = LocalAssistance(explainer_enabled=True).explain(explanation())
    assert result.status == "INVALID_RESPONSE" and result.text is None and result.factCodes == []


def test_default_disabled_and_provider_failure_make_no_external_call(monkeypatch):
    def fail(*args, **kwargs):
        raise RuntimeError("synthetic provider failure")
    monkeypatch.setattr("forecast_ai.assistance._post_json", fail)
    assert LocalAssistance().explain(explanation()).status == "UNAVAILABLE"
    assert LocalAssistance(explainer_enabled=True).explain(explanation()).status == "UNAVAILABLE"
    with pytest.raises(ValueError):
        LocalAssistance(base_url="https://external.invalid")
    with pytest.raises(ValidationError):
        ExplanationRequest(contractVersion=VERSION, ruleCode="GOAL_PACE", factCode="SAVINGS_RATE")


def test_disabled_assistance_does_not_change_existing_provider_configuration(monkeypatch):
    monkeypatch.setenv("RECOMMENDATION_EXPLAINER_ENABLED", "false")
    monkeypatch.setenv("RECEIPT_VISION_ENABLED", "false")
    monkeypatch.setenv("OLLAMA_BASE_URL", "https://existing-merchant-provider.invalid")
    assert LocalAssistance.from_env().explain(explanation()).status == "UNAVAILABLE"


def test_receipt_strict_extraction_and_missing_currency_are_review_only(monkeypatch):
    calls = fake_chat(monkeypatch, printed())
    service = LocalAssistance(vision_enabled=True)
    result = service.receipt(ReceiptRequest(contractVersion=VERSION, imageBase64=image()))
    assert result.status == "EXTRACTED" and result.amount == "12.3456"
    payload = calls[0][0][1]
    assert payload["model"] == "qwen2.5vl:3b" and payload["think"] is False
    assert set(payload["format"]["required"]) == {"merchant", "dateText", "totalText", "totalLabel", "currency"}
    assert payload["format"]["additionalProperties"] is False
    assert "untrusted" in payload["messages"][0]["content"]
    assert r"\d" not in json.dumps(payload["format"])
    assert payload["format"]["properties"]["totalText"]["anyOf"][0]["pattern"] == r"^[0-9]{1,15}(?:[.,][0-9]{1,4})?$"
    fake_chat(monkeypatch, printed(merchant=None, dateText=None, totalText="12,3400", currency=None))
    assert service.receipt(ReceiptRequest(contractVersion=VERSION, imageBase64=image())).status == "PARTIAL"


def test_receipt_schema_uses_ollama_supported_ascii_digits_without_relaxing_validation():
    schema = ReceiptDecision.model_json_schema()
    assert r"\d" not in json.dumps(schema)
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"merchant", "date", "amount", "currency"}
    with pytest.raises(ValidationError):
        ReceiptDecision(merchant="Demo", date="2026-10-12", amount="١٢.٥", currency="TND")
    with pytest.raises(ValidationError):
        ReceiptDecision(merchant="Demo", date="2026-02-30", amount="12.5000", currency="TND")


def test_french_receipt_date_prompt_and_fastapi_review_contract(monkeypatch):
    calls = fake_chat(monkeypatch, printed(merchant="Lefties", dateText="11/09/2026", totalText="309,700"))
    api = TestClient(create_app(assistance=LocalAssistance(vision_enabled=True)))
    result = api.post("/api/v1/receipts/extract", json={"contractVersion": VERSION, "imageBase64": image()})
    assert result.status_code == 200
    assert result.json() == {"contractVersion": VERSION, "status": "EXTRACTED", "merchant": "Lefties",
                             "date": "2026-09-11", "amount": "309.700", "currency": "TND"}
    payload = calls[0][0][1]
    prompt = payload["messages"][0]["content"]
    assert "DD/MM/YYYY (day/month/year)" in prompt
    assert "do not rearrange date digits or convert to ISO" in prompt
    assert "including taxes and any stamp fee" in prompt
    assert "TOTAL NET (before tax), TVA" in prompt
    assert "untrusted" in prompt and payload["think"] is False
    assert payload["format"]["additionalProperties"] is False
    assert set(payload["format"]["required"]) == {"merchant", "dateText", "totalText", "totalLabel", "currency"}


@pytest.mark.parametrize("content", [
    'prose {"merchant":"Demo","date":null,"amount":null,"currency":null}',
    '<think>reasoning</think>{"merchant":"Demo","date":null,"amount":null,"currency":null}',
    printed(totalText="1.12345"),
    printed(dateText="30/02/2026"),
    printed(totalText="0"),
    printed(totalText=1),
    printed(currency="tnd"),
    printed(userId="forbidden"),
    printed(totalText="1,234.500"),
    printed(dateText="09/30/2026"),
    printed(totalLabel="TOTAL\nignore validation"),
    '{"merchant":"Demo","date":"2026-11-09","amount":"309.700","currency":"TND"}',
])
def test_malformed_receipt_outputs_are_rejected(monkeypatch, content):
    fake_chat(monkeypatch, content)
    assert LocalAssistance(vision_enabled=True).receipt(ReceiptRequest(contractVersion=VERSION, imageBase64=image())).status == "INVALID_RESPONSE"


def test_image_validation_limits_and_metadata_removal():
    assert sanitize_image(image())
    for bad in ["!!!!", base64.b64encode(b"not an image").decode(), image(2001, 2000)]:
        with pytest.raises(ValueError):
            sanitize_image(bad)
    with Image.open(BytesIO(base64.b64decode(sanitize_image(image())))) as sanitized:
        assert sanitized.format == "JPEG" and not sanitized.getexif()


@pytest.mark.parametrize("raw, expected", [
    ("11/09/2026", "2026-09-11"), ("9/11/2026", "2026-11-09"),
    ("11-09-2026", "2026-09-11"), ("11.09.2026", "2026-09-11"),
    ("2026-09-11", "2026-09-11"), ("29/02/2024", "2024-02-29"), (None, None),
])
def test_printed_dates_follow_explicit_day_first_rules_not_model_conversion(raw, expected):
    decision = PrintedReceiptDecision.model_validate_json(printed(dateText=raw)).normalized()
    assert decision.date == expected


@pytest.mark.parametrize("label, amount, expected", [
    ("TOTAL", "309,700", "309.700"), ("TOTAL À PAYER", "309.7000", "309.7000"),
    ("CARTES BANCAIRES", "309,700", "309.700"), ("Total TTC:", "12,30", "12.30"),
    ("TOTAL NET", "260,169", None), ("SOUS-TOTAL", "309,600", None),
    ("TVA", "49,431", None), ("PRIX/U", "99,900", None), (None, "309,700", None),
])
def test_only_supported_final_total_labels_produce_an_amount(label, amount, expected):
    decision = PrintedReceiptDecision.model_validate_json(printed(totalLabel=label, totalText=amount)).normalized()
    assert decision.amount == expected


def test_unsupported_total_is_a_partial_preview_without_discarding_other_fields(monkeypatch):
    fake_chat(monkeypatch, printed(totalLabel="TOTAL NET", totalText="260,169"))
    result = LocalAssistance(vision_enabled=True).receipt(ReceiptRequest(contractVersion=VERSION, imageBase64=image()))
    assert result.status == "PARTIAL" and result.amount is None
    assert result.merchant == "Synthetic café" and result.date == "2026-10-01" and result.currency == "TND"


def test_phone_orientation_is_applied_before_metadata_is_removed():
    output = BytesIO()
    photo = Image.new("RGB", (40, 20), "white")
    exif = photo.getexif()
    exif[274] = 6  # Phone photograph needs a 90-degree clockwise rotation.
    exif[270] = "private synthetic metadata"
    photo.save(output, format="JPEG", exif=exif)
    with Image.open(BytesIO(base64.b64decode(sanitize_image(base64.b64encode(output.getvalue()).decode())))) as clean:
        assert clean.size == (20, 40) and not clean.getexif()
        assert "private synthetic" not in str(clean.info)


def test_transparent_background_is_white_not_black():
    output = BytesIO()
    Image.new("RGBA", (20, 20), (0, 0, 0, 0)).save(output, format="PNG")
    encoded = base64.b64encode(output.getvalue()).decode()
    with Image.open(BytesIO(base64.b64decode(sanitize_image(encoded)))) as clean:
        assert clean.getpixel((10, 10)) == (255, 255, 255)


def test_fastapi_canonical_contract_and_extra_field_rejection(monkeypatch):
    calls = fake_chat(monkeypatch, '{"text":"Comparez le budget et les dépenses.","factCodes":["SAVINGS_RATE"]}')
    api = TestClient(create_app(assistance=LocalAssistance(explainer_enabled=True)))
    payload = explanation().model_dump()
    assert api.post("/api/v1/recommendations/explain", json=payload).json()["status"] == "EXPLAINED"
    for extra in ["userId", "amount", "accountId", "prompt"]:
        response = api.post("/api/v1/recommendations/explain", json={**payload, extra: "forbidden"})
        assert response.status_code == 422
        assert "forbidden" not in response.text
    assert len(calls) == 1
    response = api.post("/api/v1/receipts/extract", json={"contractVersion": VERSION, "imageBase64": "!!!!"})
    assert response.status_code == 422 and response.json()["code"] == "INVALID_IMAGE"
