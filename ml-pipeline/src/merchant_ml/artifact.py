from __future__ import annotations

import hashlib
import json
import platform
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score
from sklearn.pipeline import Pipeline

from merchant_ml.catalog import (
    CATALOG_VERSION, FEATURE_VERSION, MERCHANT_CATALOG, SEED, TAXONOMY_VERSION, UNKNOWN_TEXTS,
)
from merchant_ml.normalization import NORMALIZATION_VERSION, normalize_merchant_text

MODEL_ID = "merchant-recognition-synthetic-v1"
MODEL_VERSION = "1.0.0"
ARTIFACT_NAME = "merchant_recognition_v1.joblib"
METADATA_NAME = "merchant_recognition_v1.metadata.json"
CHECKSUM_NAME = "merchant_recognition_v1.sha256"
DATASET_VERSION = "merchant-synthetic-v1"


def build_dataset() -> pd.DataFrame:
    rows: list[dict[str, str]] = []
    templates = {
        "train": ("{alias}", "paiement {alias}", "achat {alias} tunis"),
        "validation": ("achat chez {alias}", "carte {alias} centre"),
        "test": ("operation {alias}", "commande a {alias} tunisie"),
    }
    for merchant, (category, aliases) in sorted(MERCHANT_CATALOG.items()):
        for alias in aliases:
            for split, split_templates in templates.items():
                for template in split_templates:
                    rows.append({
                        "text": normalize_merchant_text(template.format(alias=alias)),
                        "merchant": merchant,
                        "category": category,
                        "split": split,
                        "known": "true",
                    })
    for index, text in enumerate(UNKNOWN_TEXTS):
        split = ("train", "validation", "test")[index % 3]
        rows.append({"text": normalize_merchant_text(text), "merchant": "", "category": "", "split": split, "known": "false"})
    frame = pd.DataFrame(rows).drop_duplicates(subset=["text"], keep=False).sort_values(["split", "text"])
    if frame.duplicated("text").any():
        raise RuntimeError("merchant variants crossed splits")
    return frame.reset_index(drop=True)


def train_and_export(output_directory: str | Path, created_at: datetime | None = None) -> dict[str, object]:
    data = build_dataset()
    known = data[data["known"] == "true"]
    train = known[known["split"] == "train"]
    validation = known[known["split"] == "validation"]
    test = known[known["split"] == "test"]
    validation_unknown = data[(data["split"] == "validation") & (data["known"] == "false")]
    test_unknown = data[(data["split"] == "test") & (data["known"] == "false")]

    selection_model = _pipeline().fit(train["text"], train["category"])
    threshold = _select_threshold(selection_model, validation, validation_unknown)
    validation_metrics = _evaluate(selection_model, validation, validation_unknown, threshold)
    # The untouched test is inspected exactly here, once, after threshold selection.
    test_metrics = _evaluate(selection_model, test, test_unknown, threshold)
    development = pd.concat([train, validation], ignore_index=True)
    final_model = _pipeline().fit(development["text"], development["category"])

    aliases = {
        normalize_merchant_text(alias): {"canonicalMerchant": merchant, "categoryCode": category}
        for merchant, (category, merchant_aliases) in MERCHANT_CATALOG.items()
        for alias in merchant_aliases
    }
    payload = {"pipeline": final_model, "aliases": aliases, "threshold": threshold,
               "featureVersion": FEATURE_VERSION, "modelId": MODEL_ID, "modelVersion": MODEL_VERSION}
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    artifact = output / ARTIFACT_NAME
    joblib.dump(payload, artifact, compress=3)
    artifact_hash = sha256_file(artifact)
    dataset_hash = hashlib.sha256(data.to_csv(index=False, lineterminator="\n").encode()).hexdigest()
    metadata = {
        "modelId": MODEL_ID, "modelVersion": MODEL_VERSION, "modelType": "char-tfidf-logistic-regression",
        "featureVersion": FEATURE_VERSION, "catalogVersion": CATALOG_VERSION,
        "taxonomyVersion": TAXONOMY_VERSION, "normalizationVersion": NORMALIZATION_VERSION,
        "datasetVersion": DATASET_VERSION, "datasetSha256": dataset_hash, "artifactSha256": artifact_hash,
        "currency": "TND", "threshold": threshold, "randomSeed": SEED,
        "experimental": True, "syntheticTrainingData": True, "guaranteed": False,
        "splitSizes": {name: int((data["split"] == name).sum()) for name in ("train", "validation", "test")},
        "validationMetrics": validation_metrics, "testMetrics": test_metrics,
        "dependencies": {"python": platform.python_version(), "pandas": pd.__version__,
                         "scikitLearn": sklearn.__version__, "joblib": joblib.__version__},
        "createdAtUtc": (created_at or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    metadata_path = output / METADATA_NAME
    checksum_path = output / CHECKSUM_NAME
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    checksum_path.write_text(f"{artifact_hash}  {ARTIFACT_NAME}\n", encoding="ascii")
    loaded = load_verified_artifact(output)
    fixtures = ["paiement carrefour", "commande a udemy tunisie"]
    np.testing.assert_allclose(final_model.predict_proba(fixtures), loaded["pipeline"].predict_proba(fixtures), rtol=0, atol=0)
    return metadata


def load_verified_artifact(directory: str | Path) -> dict[str, object]:
    root = Path(directory)
    metadata = json.loads((root / METADATA_NAME).read_text(encoding="utf-8"))
    expected = (root / CHECKSUM_NAME).read_text(encoding="ascii").split()[0]
    actual = sha256_file(root / ARTIFACT_NAME)
    if actual != expected or actual != metadata.get("artifactSha256"):
        raise ValueError("merchant artifact SHA-256 mismatch")
    artifact = joblib.load(root / ARTIFACT_NAME)
    if artifact.get("modelId") != MODEL_ID or artifact.get("featureVersion") != FEATURE_VERSION:
        raise ValueError("merchant artifact contract mismatch")
    return artifact


def _pipeline() -> Pipeline:
    return Pipeline([
        ("tfidf", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=1, sublinear_tf=True)),
        ("classifier", LogisticRegression(max_iter=1000, random_state=SEED, class_weight="balanced")),
    ])


def _select_threshold(model: Pipeline, known: pd.DataFrame, unknown: pd.DataFrame) -> float:
    known_scores = model.predict_proba(known["text"]).max(axis=1)
    unknown_scores = model.predict_proba(unknown["text"]).max(axis=1) if len(unknown) else np.array([])
    candidates = np.arange(0.35, 0.91, 0.01)
    valid = [float(value) for value in candidates if not len(unknown_scores) or float((unknown_scores >= value).mean()) <= 0.10]
    return round(min(valid or [0.90]), 2)


def _evaluate(model: Pipeline, known: pd.DataFrame, unknown: pd.DataFrame, threshold: float) -> dict[str, float | int]:
    probabilities = model.predict_proba(known["text"])
    predicted = model.classes_[probabilities.argmax(axis=1)]
    accepted = probabilities.max(axis=1) >= threshold
    labels = np.where(accepted, predicted, "UNRECOGNIZED")
    unknown_acceptance = 0.0
    if len(unknown):
        unknown_acceptance = float((model.predict_proba(unknown["text"]).max(axis=1) >= threshold).mean())
    return {"knownMacroF1": round(float(f1_score(known["category"], labels, average="macro")), 6),
            "knownAcceptanceRate": round(float(accepted.mean()), 6),
            "unknownFalseAcceptanceRate": round(unknown_acceptance, 6),
            "knownCount": int(len(known)), "unknownCount": int(len(unknown))}


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

