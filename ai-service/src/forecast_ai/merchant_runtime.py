from __future__ import annotations

import hashlib
import json
import math
import re
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path

import joblib
import numpy as np

from forecast_ai.merchant_contracts import ALLOWED_CATEGORIES, FEATURE_VERSION, MerchantItem, MerchantPrediction
from forecast_ai.runtime import ModelUnavailableError

MODEL_ID = "merchant-recognition-synthetic-v1"
MODEL_VERSION = "1.0.0"
FUZZY_THRESHOLD = 0.88


class MerchantRuntime:
    def __init__(self, model_directory: str | Path) -> None:
        root = Path(model_directory)
        self.artifact_path = root / "merchant_recognition_v1.joblib"
        self.metadata_path = root / "merchant_recognition_v1.metadata.json"
        self.checksum_path = root / "merchant_recognition_v1.sha256"
        self.artifact: dict[str, object] | None = None
        self.metadata: dict[str, object] | None = None
        self.failure_reason: str | None = None

    @property
    def available(self) -> bool:
        return self.artifact is not None and self.metadata is not None

    def load(self) -> None:
        self.artifact = None
        self.metadata = None
        try:
            metadata = json.loads(self.metadata_path.read_text(encoding="utf-8"))
            sidecar = self.checksum_path.read_text(encoding="ascii").split()
            actual = _sha256(self.artifact_path)
            if len(sidecar) != 2 or sidecar[0] != actual or metadata.get("artifactSha256") != actual:
                raise ValueError("merchant artifact hash mismatch")
            if metadata.get("modelId") != MODEL_ID or metadata.get("modelVersion") != MODEL_VERSION:
                raise ValueError("merchant model identity mismatch")
            if metadata.get("featureVersion") != FEATURE_VERSION:
                raise ValueError("merchant feature contract mismatch")
            if metadata.get("experimental") is not True or metadata.get("syntheticTrainingData") is not True or metadata.get("guaranteed") is not False:
                raise ValueError("merchant safety metadata mismatch")
            artifact = joblib.load(self.artifact_path)
            if artifact.get("modelId") != MODEL_ID or artifact.get("featureVersion") != FEATURE_VERSION:
                raise ValueError("merchant artifact contract mismatch")
            self.artifact = artifact
            self.metadata = metadata
            self.failure_reason = None
        except Exception as exception:
            self.failure_reason = type(exception).__name__

    def predict(self, item: MerchantItem) -> MerchantPrediction:
        if not self.available:
            raise ModelUnavailableError("merchant model is unavailable")
        if item.currency != "TND" or item.direction != "DEBIT":
            return self._unrecognized(item.request_key)
        text = _normalize(item.sanitized_merchant_text)
        aliases: dict[str, dict[str, str]] = self.artifact["aliases"]
        exact = aliases.get(text)
        if exact:
            return self._recognized(item.request_key, exact, "EXACT_ALIAS", 1.0)
        alias, ratio = max(
            ((candidate, SequenceMatcher(None, text, candidate).ratio()) for candidate in aliases),
            key=lambda pair: pair[1],
        )
        if ratio >= FUZZY_THRESHOLD:
            return self._recognized(item.request_key, aliases[alias], "FUZZY_ALIAS", ratio)
        pipeline = self.artifact["pipeline"]
        probabilities = np.asarray(pipeline.predict_proba([text])[0], dtype=float)
        index = int(probabilities.argmax())
        score = float(probabilities[index])
        category = str(pipeline.classes_[index])
        if not math.isfinite(score) or score < float(self.artifact["threshold"]) or category not in ALLOWED_CATEGORIES:
            return self._unrecognized(item.request_key)
        compatible = [(candidate, data) for candidate, data in aliases.items() if data["categoryCode"] == category]
        canonical_alias, canonical = max(compatible, key=lambda pair: SequenceMatcher(None, text, pair[0]).ratio())
        del canonical_alias
        return self._recognized(item.request_key, canonical, "ML", score)

    def _recognized(self, key: str, alias: dict[str, str], method: str, score: float) -> MerchantPrediction:
        return MerchantPrediction(
            request_key=key, status="RECOGNIZED", canonical_merchant=alias["canonicalMerchant"],
            category_code=alias["categoryCode"], match_method=method, match_score=round(score, 4),
            model_id=MODEL_ID, model_version=MODEL_VERSION, feature_version=FEATURE_VERSION,
            experimental=True, synthetic_training_data=True, guaranteed=False,
        )

    def _unrecognized(self, key: str) -> MerchantPrediction:
        return MerchantPrediction(
            request_key=key, status="UNRECOGNIZED", canonical_merchant=None, category_code=None,
            match_method=None, match_score=None, model_id=MODEL_ID, model_version=MODEL_VERSION,
            feature_version=FEATURE_VERSION, experimental=True, synthetic_training_data=True,
            guaranteed=False,
        )


def _normalize(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value)
    ascii_text = "".join(char for char in decomposed if not unicodedata.combining(char))
    lowered = ascii_text.lower().replace("&", " and ")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", lowered)).strip()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()
