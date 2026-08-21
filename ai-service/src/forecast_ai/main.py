"""FastAPI application for the frozen Forecast residual model."""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from forecast_ai.contracts import (
    FEATURE_VERSION,
    ModelResponse,
    PredictionRequest,
    PredictionResponse,
)
from forecast_ai.runtime import MODEL_ID, MODEL_VERSION, ModelRuntime, ModelUnavailableError
from forecast_ai.merchant_contracts import MerchantBatchRequest, MerchantBatchResponse, MerchantModelResponse
from forecast_ai.merchant_runtime import MerchantRuntime

LOGGER = logging.getLogger("forecast_ai")
DEFAULT_MODEL_DIRECTORY = Path(__file__).parents[2] / "models/forecast"


def create_app(runtime: ModelRuntime | None = None, merchant_runtime: MerchantRuntime | None = None) -> FastAPI:
    model_runtime = runtime or ModelRuntime(
        os.getenv("FORECAST_MODEL_DIRECTORY", str(DEFAULT_MODEL_DIRECTORY))
    )
    merchant_model_runtime = merchant_runtime or MerchantRuntime(
        os.getenv("MERCHANT_MODEL_DIRECTORY", str(DEFAULT_MODEL_DIRECTORY.parent / "merchant"))
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        model_runtime.load()
        merchant_model_runtime.load()
        if model_runtime.available:
            LOGGER.info("forecast_model_loaded model_id=%s model_version=%s", MODEL_ID, MODEL_VERSION)
        else:
            LOGGER.error("forecast_model_unavailable reason=%s", model_runtime.failure_reason)
        if merchant_model_runtime.available:
            LOGGER.info("merchant_model_loaded model_id=merchant-recognition-synthetic-v1 model_version=1.0.0")
        else:
            LOGGER.error("merchant_model_unavailable reason=%s", merchant_model_runtime.failure_reason)
        yield

    app = FastAPI(title="Smart Financial Wellness AI Service", version="0.1.0", lifespan=lifespan)
    app.state.model_runtime = model_runtime
    app.state.merchant_runtime = merchant_model_runtime

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exception: RequestValidationError) -> JSONResponse:
        fields = sorted(
            {".".join(str(part) for part in error["loc"] if part != "body") for error in exception.errors()}
        )
        return JSONResponse(
            status_code=422,
            content={
                "code": "VALIDATION_ERROR",
                "message": "Prediction request validation failed",
                "fields": fields,
            },
        )

    @app.get("/health")
    async def health() -> JSONResponse:
        if not model_runtime.available:
            return JSONResponse(
                status_code=503,
                content={"status": "UNAVAILABLE", "modelAvailable": False},
            )
        return JSONResponse(content={"status": "UP", "modelAvailable": True})

    @app.get("/api/v1/forecast/model", response_model=ModelResponse, response_model_by_alias=True)
    async def model() -> ModelResponse:
        if not model_runtime.available:
            raise ModelUnavailableError("forecast model is unavailable")
        return ModelResponse(
            model_id=MODEL_ID,
            model_version=MODEL_VERSION,
            feature_version=FEATURE_VERSION,
            currency="TND",
            experimental=True,
            synthetic_training_data=True,
            guaranteed=False,
        )

    @app.post(
        "/api/v1/forecast/predict-residual",
        response_model=PredictionResponse,
        response_model_by_alias=True,
    )
    async def predict(request: PredictionRequest) -> PredictionResponse:
        try:
            residual = model_runtime.predict(request)
        except ModelUnavailableError:
            LOGGER.warning("forecast_prediction outcome=unavailable model_id=%s", MODEL_ID)
            raise
        LOGGER.info("forecast_prediction outcome=success model_id=%s model_version=%s", MODEL_ID, MODEL_VERSION)
        return PredictionResponse(
            model_id=MODEL_ID,
            model_version=MODEL_VERSION,
            feature_version=FEATURE_VERSION,
            currency="TND",
            predicted_residual=f"{residual:.4f}",
            experimental=True,
            synthetic_training_data=True,
            guaranteed=False,
        )

    @app.get("/api/v1/merchant-recognition/model", response_model=MerchantModelResponse, response_model_by_alias=True)
    async def merchant_model() -> MerchantModelResponse:
        if not merchant_model_runtime.available:
            raise ModelUnavailableError("merchant model is unavailable")
        metadata = merchant_model_runtime.metadata
        return MerchantModelResponse(
            model_id=metadata["modelId"], model_version=metadata["modelVersion"],
            feature_version=metadata["featureVersion"], catalog_version=metadata["catalogVersion"],
            taxonomy_version=metadata["taxonomyVersion"], threshold=metadata["threshold"],
            experimental=True, synthetic_training_data=True, guaranteed=False,
        )

    @app.post("/api/v1/merchant-recognition/predict", response_model=MerchantBatchResponse, response_model_by_alias=True)
    async def merchant_predict(request: MerchantBatchRequest) -> MerchantBatchResponse:
        if not merchant_model_runtime.available:
            raise ModelUnavailableError("merchant model is unavailable")
        predictions = [merchant_model_runtime.predict(item) for item in request.items]
        LOGGER.info("merchant_prediction outcome=success item_count=%s", len(predictions))
        return MerchantBatchResponse(items=predictions)

    @app.exception_handler(ModelUnavailableError)
    async def unavailable(_: Request, __: ModelUnavailableError) -> JSONResponse:
        return JSONResponse(
            status_code=503,
            content={"code": "MODEL_UNAVAILABLE", "message": "Requested model is unavailable"},
        )

    return app


app = create_app()
