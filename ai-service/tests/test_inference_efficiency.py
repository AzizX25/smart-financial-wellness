"""Deterministic performance-control tests: no real Ollama or web calls."""

import json
import ssl
from http.client import RemoteDisconnected
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from forecast_ai.assistance import ExplanationRequest, LocalAssistance, VERSION
from forecast_ai.inference_control import local_inference_slot, remaining_timeout, request_budget
from forecast_ai.main import create_app
from forecast_ai.merchant_enrichment import CatalogEntry, EnrichmentProviderUnavailable, MerchantEnricher, MerchantEnrichmentSettings, ReasoningContext
from forecast_ai.merchant_runtime import MerchantRuntime
from forecast_ai.ollama_providers import OllamaEmbeddingProvider, OllamaReasoningProvider, OllamaWebSearchProvider, _post_json
from forecast_ai.runtime import ModelRuntime

ROOT = Path(__file__).parents[1]


def test_public_catalog_vectors_are_cached_but_query_text_is_not():
    settings = MerchantEnrichmentSettings()
    embedding = Mock()
    embedding.embed.side_effect = lambda texts: [[1.0, 0.5] for _ in texts]
    enricher = MerchantEnricher(settings, embedding, None, None)
    catalog = (CatalogEntry("Public merchant", "FOOD", ("public alias",)),)
    first = enricher._nearest_candidates("synthetic first query", catalog)
    second = enricher._nearest_candidates("synthetic second query", catalog)
    assert first == second
    assert embedding.embed.call_args_list[0].args[0] == ["synthetic first query", catalog[0].embedding_text]
    assert embedding.embed.call_args_list[1].args[0] == ["synthetic second query"]
    assert "synthetic" not in repr(enricher._catalog_key)
    changed = (CatalogEntry("Changed public merchant", "FOOD", ("new alias",)),)
    enricher._nearest_candidates("third query", changed)
    assert len(embedding.embed.call_args_list[2].args[0]) == 2
    enricher.settings = replace(settings, embedding_model="different-version")
    enricher._nearest_candidates("fourth query", changed)
    assert len(embedding.embed.call_args_list[3].args[0]) == 2


def test_invalid_catalog_vectors_are_never_cached():
    embedding = Mock()
    embedding.embed.side_effect = [[[1.0], [float("nan")]], [[1.0], [1.0]]]
    enricher = MerchantEnricher(MerchantEnrichmentSettings(), embedding, None, None)
    catalog = (CatalogEntry("Public", "FOOD", ()),)
    with pytest.raises(ValueError):
        enricher._nearest_candidates("query", catalog)
    assert enricher._catalog_key is None
    enricher._nearest_candidates("query", catalog)
    assert len(embedding.embed.call_args_list[1].args[0]) == 2


def test_cpu_embeddings_use_small_context_and_no_retries(monkeypatch):
    post = Mock(return_value={"embeddings": [[1.0, 0.5]]})
    monkeypatch.setattr("forecast_ai.ollama_providers._post_json", post)
    provider = OllamaEmbeddingProvider(MerchantEnrichmentSettings(embedding_cpu_only=True))
    assert provider.embed(["synthetic query"]) == [[1.0, 0.5]]
    assert post.call_args.args[1]["options"] == {"num_ctx": 1024, "num_gpu": 0}
    assert post.call_args.kwargs == {"retries": 0}


def test_budget_is_shared_by_stages_and_nested_calls_cannot_extend_it(monkeypatch):
    clock = [100.0]
    monkeypatch.setattr("forecast_ai.inference_control.monotonic", lambda: clock[0])
    with request_budget(10):
        assert remaining_timeout(30) == 10
        clock[0] = 107
        with request_budget(30):
            assert remaining_timeout(30) == 3
        clock[0] = 111
        with pytest.raises(TimeoutError):
            remaining_timeout(30)
    assert remaining_timeout(30) == 30


def test_transport_uses_remaining_budget_and_does_not_retry_timeout(monkeypatch):
    class Response:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def read(self, limit):
            assert limit == 4 * 1024 * 1024 + 1
            return b'{"status":"synthetic"}'

    monkeypatch.setattr("forecast_ai.inference_control.monotonic", lambda: 100.0)
    transport = Mock(return_value=Response())
    monkeypatch.setattr("forecast_ai.ollama_providers.urlopen", transport)
    with request_budget(5):
        assert _post_json("http://localhost/synthetic", {}, {}, 30, retries=0)["status"] == "synthetic"
    assert transport.call_args.kwargs["timeout"] == 5
    transport.side_effect = TimeoutError()
    transport.reset_mock()
    with pytest.raises(EnrichmentProviderUnavailable):
        _post_json("http://localhost/synthetic", {}, {}, 30, retries=0)
    assert transport.call_count == 1


def test_busy_local_inference_is_safe_for_both_merchant_and_assistance(monkeypatch):
    post = Mock(return_value={"message": {"content": json.dumps({"text": "Relisez votre budget.", "factCodes": ["SAVINGS_RATE"]})}})
    monkeypatch.setattr("forecast_ai.assistance._post_json", post)
    merchant_post = Mock()
    monkeypatch.setattr("forecast_ai.ollama_providers._post_json", merchant_post)
    provider = OllamaReasoningProvider(MerchantEnrichmentSettings())
    assistance = LocalAssistance(explainer_enabled=True)
    request = ExplanationRequest(contractVersion=VERSION, ruleCode="SAVINGS_MARGIN", factCode="SAVINGS_RATE")
    with local_inference_slot():
        assert assistance.explain(request).status == "UNAVAILABLE"
        with pytest.raises(EnrichmentProviderUnavailable):
            provider.reason(ReasoningContext("synthetic", (), ("FOOD",), ()))
    assert not post.called and not merchant_post.called
    assert assistance.explain(request).status == "EXPLAINED"


@pytest.mark.parametrize("failure", [RemoteDisconnected(), ConnectionResetError(), ssl.SSLError()])
def test_provider_disconnects_return_safe_unavailable_without_retry(monkeypatch, failure):
    transport = Mock(side_effect=failure)
    monkeypatch.setattr("forecast_ai.ollama_providers.urlopen", transport)
    with pytest.raises(EnrichmentProviderUnavailable):
        _post_json("https://provider.invalid/synthetic", {}, {}, 20, retries=0)
    assert transport.call_count == 1


def test_search_disconnect_returns_a_complete_safe_merchant_batch(monkeypatch):
    settings = MerchantEnrichmentSettings(enabled=True, api_key="synthetic-not-a-real-key")
    embedding = Mock()
    embedding.embed.side_effect = lambda texts: [[1.0, 0.5] for _ in texts]
    reasoning = Mock()
    enricher = MerchantEnricher(settings, embedding, OllamaWebSearchProvider(settings), reasoning)
    runtime = MerchantRuntime(ROOT / "models/merchant", enricher)
    monkeypatch.setattr("forecast_ai.ollama_providers.urlopen", Mock(side_effect=RemoteDisconnected()))
    request = {"items": [{"requestKey": key, "sanitizedMerchantText": "zxqv synthetic unknown",
                          "direction": "DEBIT", "currency": "TND",
                          "featureVersion": "merchant-recognition-features-v1"}
                         for key in ("first", "second")]}
    with TestClient(create_app(ModelRuntime(ROOT / "models/forecast"), runtime)) as client:
        response = client.post("/api/v1/merchant-recognition/predict", json=request)
    assert response.status_code == 200
    assert [item["requestKey"] for item in response.json()["items"]] == ["first", "second"]
    assert all(item["fallbackReason"] == "ENRICHMENT_UNAVAILABLE"
               and item["categoryCode"] is None and item["evidenceUrls"] == []
               for item in response.json()["items"])
    reasoning.reason.assert_not_called()


def test_expired_merchant_batch_returns_one_safe_result_per_key(monkeypatch):
    runtime = MerchantRuntime(ROOT / "models/merchant")
    monkeypatch.setattr(runtime, "predict", Mock(side_effect=TimeoutError("synthetic expired budget")))
    request = {"items": [{"requestKey": key, "sanitizedMerchantText": "carrefour", "direction": "DEBIT",
                          "currency": "TND", "featureVersion": "merchant-recognition-features-v1"}
                         for key in ("first", "second")]}
    with TestClient(create_app(ModelRuntime(ROOT / "models/forecast"), runtime)) as client:
        response = client.post("/api/v1/merchant-recognition/predict", json=request)
    assert response.status_code == 200
    assert [item["requestKey"] for item in response.json()["items"]] == ["first", "second"]
    assert all(item["status"] == "UNRECOGNIZED" and item["fallbackReason"] == "ENRICHMENT_UNAVAILABLE"
               and item["canonicalMerchant"] is None and item["evidenceUrls"] == []
               for item in response.json()["items"])
    assert runtime.predict.call_count == 1


def test_assistance_timeout_configuration_is_bounded_and_default_flags_stay_off(monkeypatch):
    monkeypatch.setenv("RECOMMENDATION_EXPLAINER_ENABLED", "true")
    monkeypatch.setenv("OLLAMA_BASE_URL", "http://localhost:11434")
    monkeypatch.setenv("OLLAMA_ASSISTANCE_TIMEOUT_SECONDS", "50")
    assert LocalAssistance.from_env().timeout == 50
    with pytest.raises(ValueError):
        LocalAssistance(timeout=51)
    monkeypatch.setenv("RECOMMENDATION_EXPLAINER_ENABLED", "false")
    monkeypatch.setenv("RECEIPT_VISION_ENABLED", "false")
    assert not LocalAssistance.from_env().explainer_enabled
