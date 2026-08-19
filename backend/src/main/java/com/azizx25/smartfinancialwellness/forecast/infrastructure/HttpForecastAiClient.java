package com.azizx25.smartfinancialwellness.forecast.infrastructure;

import java.math.BigDecimal;

import com.azizx25.smartfinancialwellness.forecast.ForecastFeatureAssembler.ForecastFeatures;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiExceptions.InvalidResponse;
import com.azizx25.smartfinancialwellness.forecast.infrastructure.ForecastAiExceptions.Unavailable;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class HttpForecastAiClient implements ForecastAiClient {

    private final RestClient restClient;

    HttpForecastAiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ForecastAiPrediction predict(String currency, ForecastFeatures features) {
        PredictionRequest request = new PredictionRequest(
                features.featureVersion(), currency, features.calendarMonth(),
                features.cutoffDay(), features.daysRemaining(),
                money(features.monthToDateCredit()), money(features.monthToDateDebit()),
                money(features.monthToDateNetFlow()), features.monthToDateTransactionCount(),
                money(features.trailingSixMonthCredit()), money(features.trailingSixMonthDebit()),
                money(features.trailingSixMonthNetFlow()),
                money(features.historicalRemainingNetFlowRange()),
                money(features.deterministicRemainingIncome()),
                money(features.deterministicRemainingExpense()),
                money(features.deterministicRemainingNetFlow()),
                features.usableHistoryCount(), features.deterministicFallbackReason());
        try {
            ForecastAiPrediction response = restClient.post()
                    .uri("/api/v1/forecast/predict-residual")
                    .body(request)
                    .retrieve()
                    .body(ForecastAiPrediction.class);
            if (response == null) {
                throw new InvalidResponse("AI forecast response was empty");
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw new Unavailable(exception);
        } catch (InvalidResponse exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new InvalidResponse(exception);
        }
    }

    private String money(BigDecimal value) {
        return value.toPlainString();
    }

    private record PredictionRequest(
            String featureVersion, String currency, String calendarMonth,
            int cutoffDay, int daysRemaining,
            String monthToDateCredit, String monthToDateDebit, String monthToDateNetFlow,
            int monthToDateTransactionCount,
            String trailingSixMonthCredit, String trailingSixMonthDebit,
            String trailingSixMonthNetFlow, String historicalRemainingNetFlowRange,
            String deterministicRemainingIncome, String deterministicRemainingExpense,
            String deterministicRemainingNetFlow, int usableHistoryCount,
            String deterministicFallbackReason) {
    }
}
