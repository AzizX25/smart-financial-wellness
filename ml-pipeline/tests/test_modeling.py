from __future__ import annotations

from collections.abc import Callable

import numpy as np
import pandas as pd
import pytest
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from forecast_ml.feature_matrix import build_experiment_matrix
from forecast_ml.modeling import (
    CANDIDATE_MODEL_NAMES,
    HIST_GRADIENT_BOOSTING_MODEL,
    HUBER_MODEL,
    RESIDUAL_ZERO_MODEL,
    RIDGE_MODEL,
    create_candidate_pipeline,
    create_candidate_pipelines,
    linear_coefficients,
)
from forecast_ml.temporal_split import chronological_split


def test_every_candidate_is_a_fresh_sklearn_pipeline() -> None:
    first = create_candidate_pipelines(random_seed=7)
    second = create_candidate_pipelines(random_seed=7)

    assert tuple(first) == CANDIDATE_MODEL_NAMES
    assert all(isinstance(pipeline, Pipeline) for pipeline in first.values())
    assert all(first[name] is not second[name] for name in CANDIDATE_MODEL_NAMES)
    assert all(
        first[name].named_steps["model"] is not second[name].named_steps["model"]
        for name in CANDIDATE_MODEL_NAMES
    )


def test_numeric_preprocessing_is_fitted_only_through_training_pipeline(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    training = chronological_split(
        build_experiment_matrix(processed_frame_factory())
    ).training
    pipeline = create_candidate_pipeline(RIDGE_MODEL)
    preprocessor = pipeline.named_steps["preprocess"]
    unfitted_scaler = preprocessor.transformers[0][1]

    assert isinstance(unfitted_scaler, StandardScaler)
    assert not hasattr(unfitted_scaler, "mean_")

    pipeline.fit(training.features, training.target)
    fitted_scaler = pipeline.named_steps["preprocess"].named_transformers_["numeric"]
    assert hasattr(fitted_scaler, "mean_")


@pytest.mark.parametrize("model_name", CANDIDATE_MODEL_NAMES)
def test_candidate_pipeline_can_fit_tiny_training_fixture(
    model_name: str,
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    training = chronological_split(
        build_experiment_matrix(processed_frame_factory())
    ).training
    pipeline = create_candidate_pipeline(model_name, random_seed=123)

    predictions = pipeline.fit(training.features, training.target).predict(
        training.features.iloc[:4]
    )

    assert predictions.shape == (4,)
    assert np.isfinite(predictions).all()
    if model_name == RESIDUAL_ZERO_MODEL:
        assert np.array_equal(predictions, np.zeros(4))


def test_fixed_seed_reproduces_histogram_gradient_boosting_predictions(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    training = chronological_split(
        build_experiment_matrix(processed_frame_factory())
    ).training
    first = create_candidate_pipeline(HIST_GRADIENT_BOOSTING_MODEL, random_seed=42)
    second = create_candidate_pipeline(HIST_GRADIENT_BOOSTING_MODEL, random_seed=42)

    first_predictions = first.fit(training.features, training.target).predict(training.features)
    second_predictions = second.fit(training.features, training.target).predict(training.features)

    assert np.array_equal(first_predictions, second_predictions)


def test_linear_models_scale_numeric_features() -> None:
    for model_name in (RIDGE_MODEL, HUBER_MODEL):
        preprocessor = create_candidate_pipeline(model_name).named_steps["preprocess"]
        assert isinstance(preprocessor.transformers[0][1], StandardScaler)


def test_fitted_linear_coefficients_have_explicit_transformed_feature_names(
    processed_frame_factory: Callable[..., pd.DataFrame],
) -> None:
    training = chronological_split(
        build_experiment_matrix(processed_frame_factory())
    ).training
    pipeline = create_candidate_pipeline(RIDGE_MODEL).fit(training.features, training.target)

    coefficients = linear_coefficients(pipeline)

    assert not coefficients.empty
    assert {"feature", "coefficient"} == set(coefficients.columns)
    assert coefficients["feature"].is_unique
    assert coefficients["coefficient"].map(np.isfinite).all()


def test_unknown_candidate_is_rejected() -> None:
    with pytest.raises(ValueError, match="unknown candidate"):
        create_candidate_pipeline("automatic_winner")
