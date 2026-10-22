from __future__ import annotations

import math
import os
from dataclasses import dataclass
from threading import Lock
from typing import Annotated, Literal, Mapping, Protocol, Sequence
from urllib.parse import urlsplit

from pydantic import Field, RootModel, field_validator

from forecast_ai.merchant_contracts import ALLOWED_CATEGORIES, StrictModel

ENRICHMENT_FEATURE_VERSION = "merchant-enrichment-features-v1"
ENRICHMENT_PROVIDER_ID = "ollama"
ENRICHMENT_MODEL_VERSION = "ollama-api-v1"
MAX_CATALOG_CANDIDATES = 3
MAX_SEARCH_RESULTS = 3
MAX_SEARCH_TITLE_LENGTH = 160
MAX_SEARCH_SNIPPET_LENGTH = 500
MAX_EVIDENCE_URL_LENGTH = 2048
AllowedCategory = Literal[
    "FOOD", "BILLS_SUBSCRIPTIONS", "TRANSPORT", "HEALTH", "SHOPPING",
    "LEISURE", "EDUCATION", "HOUSING", "BANK_FEES", "TAXES",
]


class EnrichmentProviderUnavailable(RuntimeError):
    """A bounded optional provider could not complete its request."""


@dataclass(frozen=True)
class MerchantEnrichmentSettings:
    enabled: bool = False
    ollama_base_url: str = "http://host.docker.internal:11434"
    reasoning_model: str = "qwen3:4b"
    embedding_model: str = "qwen3-embedding:0.6b"
    api_key: str = ""
    web_search_url: str = "https://ollama.com/api/web_search"
    connect_timeout_seconds: float = 2.0
    local_read_timeout_seconds: float = 12.0
    search_read_timeout_seconds: float = 8.0
    context_size: int = 4096
    embedding_cpu_only: bool = False
    embedding_context_size: int = 1024
    batch_timeout_seconds: float = 80.0

    @classmethod
    def from_env(cls) -> MerchantEnrichmentSettings:
        return cls(
            enabled=_boolean_env("MERCHANT_ENRICHMENT_ENABLED", False),
            ollama_base_url=os.getenv("OLLAMA_BASE_URL", cls.ollama_base_url).rstrip("/"),
            reasoning_model=os.getenv("OLLAMA_REASONING_MODEL", cls.reasoning_model),
            embedding_model=os.getenv("OLLAMA_EMBEDDING_MODEL", cls.embedding_model),
            api_key=os.getenv("OLLAMA_API_KEY", ""),
            web_search_url=os.getenv("OLLAMA_WEB_SEARCH_URL", cls.web_search_url),
            connect_timeout_seconds=_bounded_float("OLLAMA_CONNECT_TIMEOUT_SECONDS", 2.0, 0.2, 10.0),
            local_read_timeout_seconds=_bounded_float("OLLAMA_READ_TIMEOUT_SECONDS", 12.0, 1.0, 30.0),
            search_read_timeout_seconds=_bounded_float("OLLAMA_SEARCH_TIMEOUT_SECONDS", 8.0, 1.0, 20.0),
            context_size=_bounded_int("OLLAMA_CONTEXT_SIZE", 4096, 2048, 8192),
            embedding_cpu_only=_boolean_env("OLLAMA_EMBEDDING_CPU_ONLY", False),
            embedding_context_size=_bounded_int("OLLAMA_EMBEDDING_CONTEXT_SIZE", 1024, 512, 4096),
            batch_timeout_seconds=_bounded_float("MERCHANT_BATCH_TIMEOUT_SECONDS", 80.0, 1.0, 80.0),
        )


@dataclass(frozen=True)
class CatalogEntry:
    canonical_merchant: str
    category_code: str
    aliases: tuple[str, ...]

    @property
    def embedding_text(self) -> str:
        return " ".join((self.canonical_merchant, *self.aliases))[:500]


@dataclass(frozen=True)
class CatalogCandidate:
    canonical_merchant: str
    category_code: str
    similarity: float


@dataclass(frozen=True)
class SearchResult:
    title: str
    url: str
    snippet: str


@dataclass(frozen=True)
class ReasoningContext:
    sanitized_merchant_text: str
    catalog_candidates: tuple[CatalogCandidate, ...]
    allowed_categories: tuple[str, ...]
    search_results: tuple[SearchResult, ...]


class EmbeddingProvider(Protocol):
    def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


class MerchantSearchProvider(Protocol):
    def search(self, query: str, max_results: int) -> list[SearchResult]: ...


class MerchantReasoningProvider(Protocol):
    def reason(self, context: ReasoningContext) -> Mapping[str, object]: ...


class WebSuggestionDecision(StrictModel):
    decision: Literal["SUGGEST"]
    canonical_merchant: str = Field(max_length=160)
    category_code: AllowedCategory
    rationale: str = Field(min_length=1, max_length=300)
    evidence_urls: list[str] = Field(min_length=1, max_length=MAX_SEARCH_RESULTS)

    @field_validator("canonical_merchant")
    @classmethod
    def bounded_merchant(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("canonical merchant must not be blank")
        return normalized

    @field_validator("evidence_urls")
    @classmethod
    def safe_evidence_urls(cls, values: list[str]) -> list[str]:
        if len(values) != len(set(values)):
            raise ValueError("evidence URLs must be unique")
        for value in values:
            if len(value) > MAX_EVIDENCE_URL_LENGTH or not is_safe_evidence_url(value):
                raise ValueError("evidence URL must use HTTPS")
        return values


class WebAbstainDecision(StrictModel):
    decision: Literal["ABSTAIN"]
    canonical_merchant: None
    category_code: None
    rationale: str = Field(min_length=1, max_length=300)
    evidence_urls: list[str] = Field(max_length=0)


WebDecision = Annotated[
    WebSuggestionDecision | WebAbstainDecision,
    Field(discriminator="decision"),
]


class WebReasoningDecision(RootModel[WebDecision]):
    @classmethod
    def model_json_schema(cls, by_alias: bool = True, **kwargs) -> dict[str, object]:
        del kwargs
        return {
            "title": "WebReasoningDecision",
            "oneOf": [
                WebSuggestionDecision.model_json_schema(by_alias=by_alias),
                WebAbstainDecision.model_json_schema(by_alias=by_alias),
            ],
        }


@dataclass(frozen=True)
class EnrichmentOutcome:
    recognized: bool
    fallback_reason: str | None
    canonical_merchant: str | None = None
    category_code: str | None = None
    rationale: str | None = None
    evidence_urls: tuple[str, ...] = ()


class MerchantEnricher:
    def __init__(
            self,
            settings: MerchantEnrichmentSettings,
            embedding_provider: EmbeddingProvider | None,
            search_provider: MerchantSearchProvider | None,
            reasoning_provider: MerchantReasoningProvider | None) -> None:
        self.settings = settings
        self.embedding_provider = embedding_provider
        self.search_provider = search_provider
        self.reasoning_provider = reasoning_provider
        # One bounded cache of public catalog vectors only. Never cache user queries.
        self._catalog_lock = Lock()
        self._catalog_key = None
        self._catalog_vectors: tuple[tuple[float, ...], ...] = ()

    @property
    def available(self) -> bool:
        return (
            self.settings.enabled
            and bool(self.settings.api_key)
            and self.embedding_provider is not None
            and self.search_provider is not None
            and self.reasoning_provider is not None
        )

    def enrich(self, merchant_text: str, catalog: Sequence[CatalogEntry]) -> EnrichmentOutcome:
        if not self.settings.enabled:
            return EnrichmentOutcome(False, "ENRICHMENT_DISABLED")
        if not self.available:
            return EnrichmentOutcome(False, "ENRICHMENT_NOT_CONFIGURED")
        try:
            candidates = self._nearest_candidates(merchant_text, catalog)
            query = f"{merchant_text} Tunisie"
            results = tuple(self.search_provider.search(query, MAX_SEARCH_RESULTS)[:MAX_SEARCH_RESULTS])
            context = ReasoningContext(
                sanitized_merchant_text=merchant_text,
                catalog_candidates=candidates,
                allowed_categories=tuple(sorted(ALLOWED_CATEGORIES)),
                search_results=results,
            )
            decision = WebReasoningDecision.model_validate(
                self.reasoning_provider.reason(context)
            ).root
            if decision.decision == "ABSTAIN":
                return EnrichmentOutcome(False, "MODEL_ABSTAINED")
            returned_urls = {result.url for result in results}
            if not set(decision.evidence_urls).issubset(returned_urls):
                return EnrichmentOutcome(False, "INVALID_EVIDENCE")
            return EnrichmentOutcome(
                True,
                None,
                decision.canonical_merchant,
                decision.category_code,
                decision.rationale,
                tuple(decision.evidence_urls),
            )
        except EnrichmentProviderUnavailable:
            return EnrichmentOutcome(False, "ENRICHMENT_UNAVAILABLE")
        except (TypeError, ValueError):
            return EnrichmentOutcome(False, "ENRICHMENT_INVALID_RESPONSE")

    def _nearest_candidates(
            self, merchant_text: str, catalog: Sequence[CatalogEntry]) -> tuple[CatalogCandidate, ...]:
        if not catalog:
            raise ValueError("merchant catalog is empty")
        key = (tuple(catalog), self.settings.embedding_model, self.settings.embedding_context_size,
               self.settings.embedding_cpu_only)
        with self._catalog_lock:
            if key != self._catalog_key:
                embeddings = self.embedding_provider.embed(
                    [merchant_text, *(entry.embedding_text for entry in catalog)]
                )
                if len(embeddings) != len(catalog) + 1:
                    raise ValueError("unexpected embedding count")
                vectors = tuple(tuple(vector) for vector in embeddings[1:])
                for vector in vectors:
                    _cosine(vector, vector)  # Reject invalid vectors before caching.
                self._catalog_vectors = vectors
                self._catalog_key = key
                query = embeddings[0]
            else:
                embeddings = self.embedding_provider.embed([merchant_text])
                if len(embeddings) != 1:
                    raise ValueError("unexpected embedding count")
                query = embeddings[0]
            catalog_vectors = self._catalog_vectors
        scored = [
            CatalogCandidate(entry.canonical_merchant, entry.category_code, _cosine(query, vector))
            for entry, vector in zip(catalog, catalog_vectors, strict=True)
        ]
        scored.sort(key=lambda item: (-item.similarity, item.canonical_merchant, item.category_code))
        return tuple(scored[:MAX_CATALOG_CANDIDATES])


def _cosine(left: Sequence[float], right: Sequence[float]) -> float:
    if not left or len(left) != len(right):
        raise ValueError("incompatible embedding dimensions")
    if not all(math.isfinite(value) for value in (*left, *right)):
        raise ValueError("non-finite embedding")
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        raise ValueError("zero embedding")
    return sum(a * b for a, b in zip(left, right, strict=True)) / (left_norm * right_norm)


def is_safe_evidence_url(value: str) -> bool:
    if any(character.isspace() for character in value):
        return False
    parsed = urlsplit(value)
    return (
        parsed.scheme == "https"
        and parsed.hostname is not None
        and parsed.username is None
        and parsed.password is None
    )


def _boolean_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _bounded_float(name: str, default: float, minimum: float, maximum: float) -> float:
    try:
        value = float(os.getenv(name, str(default)))
    except ValueError:
        return default
    return min(maximum, max(minimum, value))


def _bounded_int(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError:
        return default
    return min(maximum, max(minimum, value))
