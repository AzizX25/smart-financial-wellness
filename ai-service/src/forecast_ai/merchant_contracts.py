from __future__ import annotations

import re
import uuid
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

FEATURE_VERSION = "merchant-recognition-features-v1"
ALLOWED_CATEGORIES = frozenset({
    "FOOD", "BILLS_SUBSCRIPTIONS", "TRANSPORT", "HEALTH", "SHOPPING",
    "LEISURE", "EDUCATION", "HOUSING", "BANK_FEES", "TAXES",
})
AllowedCategory = Literal[
    "FOOD", "BILLS_SUBSCRIPTIONS", "TRANSPORT", "HEALTH", "SHOPPING",
    "LEISURE", "EDUCATION", "HOUSING", "BANK_FEES", "TAXES",
]
_SENSITIVE = re.compile(
    r"(?:\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|"
    r"(?:\+?\d[\d\s.-]{7,}\d)|\b\d{8,}\b)", re.IGNORECASE,
)


def _camel(value: str) -> str:
    first, *rest = value.split("_")
    return first + "".join(item.title() for item in rest)


class StrictModel(BaseModel):
    model_config = ConfigDict(alias_generator=_camel, populate_by_name=True, extra="forbid", strict=True)


class MerchantItem(StrictModel):
    request_key: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    sanitized_merchant_text: str = Field(min_length=1, max_length=160)
    direction: Literal["DEBIT", "CREDIT"]
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    feature_version: str

    @field_validator("request_key")
    @classmethod
    def reject_database_uuid(cls, value: str) -> str:
        try:
            uuid.UUID(value)
        except ValueError:
            return value
        raise ValueError("requestKey must not be a database UUID")

    @field_validator("sanitized_merchant_text")
    @classmethod
    def reject_sensitive_text(cls, value: str) -> str:
        if _SENSITIVE.search(value):
            raise ValueError("sanitizedMerchantText contains sensitive data")
        return value

    @field_validator("feature_version")
    @classmethod
    def validate_feature_version(cls, value: str) -> str:
        if value != FEATURE_VERSION:
            raise ValueError("unsupported merchant feature version")
        return value


class MerchantBatchRequest(StrictModel):
    items: list[MerchantItem] = Field(min_length=1, max_length=50)


class MerchantPrediction(StrictModel):
    request_key: str
    status: Literal["RECOGNIZED", "UNRECOGNIZED"]
    canonical_merchant: str | None
    category_code: str | None
    match_method: Literal["EXACT_ALIAS", "FUZZY_ALIAS", "ML", "WEB_LLM"] | None
    match_score: float | None
    model_id: str
    model_version: str
    feature_version: str
    experimental: bool
    synthetic_training_data: bool
    guaranteed: bool
    fallback_reason: str | None = Field(default=None, max_length=100)
    rationale: str | None = Field(default=None, max_length=300)
    evidence_urls: list[str] = Field(default_factory=list, max_length=3)
    provider_id: str | None = Field(default=None, max_length=60)
    catalog_version: str | None = Field(default=None, max_length=100)
    taxonomy_version: str | None = Field(default=None, max_length=100)
    candidate_category_codes: list[AllowedCategory] = Field(default_factory=list, max_length=3)

    @field_validator("candidate_category_codes")
    @classmethod
    def require_unique_candidates(cls, value: list[AllowedCategory]) -> list[AllowedCategory]:
        if len(value) != len(set(value)):
            raise ValueError("candidate category codes must be unique")
        return value

    @model_validator(mode="after")
    def recognized_results_have_no_candidates(self) -> MerchantPrediction:
        if self.status == "RECOGNIZED" and self.candidate_category_codes:
            raise ValueError("recognized results cannot contain fallback candidates")
        return self


class MerchantBatchResponse(StrictModel):
    items: list[MerchantPrediction]


class MerchantModelResponse(StrictModel):
    model_id: str
    model_version: str
    feature_version: str
    catalog_version: str
    taxonomy_version: str
    threshold: float
    experimental: bool
    synthetic_training_data: bool
    guaranteed: bool
    enrichment_enabled: bool
    enrichment_available: bool
    enrichment_provider_id: str | None
    enrichment_reasoning_model: str | None
    enrichment_embedding_model: str | None
    enrichment_feature_version: str | None
