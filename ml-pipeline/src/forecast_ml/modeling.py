"""Fixed, reproducible candidate pipelines without automatic model selection."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Final

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, RegressorMixin
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.linear_model import HuberRegressor, Ridge
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.utils.validation import check_is_fitted

from forecast_ml.feature_matrix import (
    CATEGORICAL_FEATURE_COLUMNS,
    NUMERIC_FEATURE_COLUMNS,
)

RESIDUAL_ZERO_MODEL = "residual_zero"
RIDGE_MODEL = "ridge"
HUBER_MODEL = "huber"
HIST_GRADIENT_BOOSTING_MODEL = "hist_gradient_boosting"
CANDIDATE_MODEL_NAMES: Final[tuple[str, ...]] = (
    RESIDUAL_ZERO_MODEL,
    RIDGE_MODEL,
    HUBER_MODEL,
    HIST_GRADIENT_BOOSTING_MODEL,
)

_FALLBACK_CATEGORIES = [["NONE", "LIMITED_HISTORY", "NO_USABLE_HISTORY"]]


class ResidualZeroRegressor(RegressorMixin, BaseEstimator):
    """Mandatory deterministic reference: always predict a zero residual."""

    def fit(self, features: pd.DataFrame, target: np.ndarray) -> "ResidualZeroRegressor":
        if len(features) != len(target):
            raise ValueError("features and target must have equal row counts")
        self.is_fitted_ = True
        self.n_features_in_ = features.shape[1]
        return self

    def predict(self, features: pd.DataFrame) -> np.ndarray:
        check_is_fitted(self, "is_fitted_")
        return np.zeros(len(features), dtype=float)


def create_candidate_pipeline(
    model_name: str,
    *,
    random_seed: int = 20260818,
) -> Pipeline:
    """Create a fresh candidate with a fixed, inspectable configuration."""
    if model_name == RESIDUAL_ZERO_MODEL:
        return Pipeline([("model", ResidualZeroRegressor())])
    if model_name == RIDGE_MODEL:
        return Pipeline(
            [
                ("preprocess", _preprocessor(scale_numeric=True)),
                ("model", Ridge(alpha=10.0)),
            ]
        )
    if model_name == HUBER_MODEL:
        return Pipeline(
            [
                ("preprocess", _preprocessor(scale_numeric=True)),
                (
                    "model",
                    HuberRegressor(
                        epsilon=1.35,
                        alpha=0.0001,
                        max_iter=1000,
                        tol=1e-5,
                    ),
                ),
            ]
        )
    if model_name == HIST_GRADIENT_BOOSTING_MODEL:
        return Pipeline(
            [
                ("preprocess", _preprocessor(scale_numeric=False)),
                (
                    "model",
                    HistGradientBoostingRegressor(
                        loss="squared_error",
                        learning_rate=0.05,
                        max_iter=150,
                        max_leaf_nodes=7,
                        max_depth=3,
                        min_samples_leaf=10,
                        l2_regularization=1.0,
                        random_state=random_seed,
                    ),
                ),
            ]
        )
    raise ValueError(
        f"unknown candidate model {model_name!r}; expected one of "
        + ", ".join(CANDIDATE_MODEL_NAMES)
    )


def create_candidate_pipelines(*, random_seed: int = 20260818) -> dict[str, Pipeline]:
    return {
        name: create_candidate_pipeline(name, random_seed=random_seed)
        for name in CANDIDATE_MODEL_NAMES
    }


def linear_coefficients(pipeline: Pipeline) -> pd.DataFrame:
    """Return fitted Ridge/Huber coefficients; magnitude is not causality."""
    check_is_fitted(pipeline)
    preprocess = pipeline.named_steps.get("preprocess")
    model = pipeline.named_steps.get("model")
    if preprocess is None or not isinstance(model, (Ridge, HuberRegressor)):
        raise ValueError("linear coefficients are available only for fitted Ridge or Huber")
    feature_names = preprocess.get_feature_names_out()
    coefficients = np.asarray(model.coef_, dtype=float)
    return pd.DataFrame(
        {"feature": feature_names, "coefficient": coefficients}
    ).sort_values("coefficient", key=lambda values: values.abs(), ascending=False)


def candidate_configuration(pipelines: Mapping[str, Pipeline]) -> pd.DataFrame:
    """Provide concise configuration evidence without fitting any model."""
    rows = []
    for name in CANDIDATE_MODEL_NAMES:
        pipeline = pipelines[name]
        estimator = pipeline.named_steps["model"]
        rows.append(
            {
                "model": name,
                "estimator": type(estimator).__name__,
                "parameters": estimator.get_params(deep=False),
            }
        )
    return pd.DataFrame(rows)


def _preprocessor(*, scale_numeric: bool) -> ColumnTransformer:
    numeric_transformer: str | StandardScaler
    numeric_transformer = StandardScaler() if scale_numeric else "passthrough"
    categorical_transformer = OneHotEncoder(
        categories=_FALLBACK_CATEGORIES,
        handle_unknown="ignore",
        sparse_output=False,
    )
    return ColumnTransformer(
        transformers=[
            ("numeric", numeric_transformer, list(NUMERIC_FEATURE_COLUMNS)),
            (
                "fallback",
                categorical_transformer,
                list(CATEGORICAL_FEATURE_COLUMNS),
            ),
        ],
        remainder="drop",
        verbose_feature_names_out=False,
    )
