package com.azizx25.smartfinancialwellness.forecast.infrastructure;

import com.azizx25.smartfinancialwellness.forecast.ForecastFeatureAssembler.ForecastFeatures;

public interface ForecastAiClient {
    ForecastAiPrediction predict(String currency, ForecastFeatures features);

    record ForecastAiPrediction(
            String modelId, String modelVersion, String featureVersion, String currency,
            String predictedResidual, boolean experimental, boolean syntheticTrainingData,
            boolean guaranteed) {
    }
}
