package com.azizx25.smartfinancialwellness.forecast.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

public final class ForecastDtos {
    private ForecastDtos() {
    }

    public record ForecastResponse(
            boolean available, String unavailableReason, UUID id, String currency,
            String timeZone, String forecastMonth, LocalDate cutoffDate, Instant snapshotCutoff,
            BigDecimal startingBalance, BigDecimal deterministicRemainingIncome,
            BigDecimal deterministicRemainingExpense, BigDecimal deterministicRemainingNetFlow,
            BigDecimal deterministicPredictedEndBalance, String mlStatus,
            BigDecimal mlPredictedResidual, BigDecimal finalPredictedEndBalance,
            String deterministicMethod, String deterministicMethodVersion,
            String modelId, String modelVersion, String featureVersion,
            boolean experimental, boolean syntheticTrainingData, boolean guaranteed,
            Instant createdAt) {
    }

    public record ForecastPageResponse(
            List<ForecastResponse> items, int page, int size, long totalElements, int totalPages) {
        public ForecastPageResponse { items = List.copyOf(items); }
    }

    public record ForecastSimulationRequest(
            @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal additionalIncome,
            @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal additionalExpense) {
    }

    public record ForecastSimulationResponse(
            UUID forecastId, String currency, BigDecimal basePredictedEndBalance,
            BigDecimal additionalIncome, BigDecimal additionalExpense,
            BigDecimal simulatedEndBalance, String mlStatus, String modelId,
            String modelVersion, boolean experimental, boolean syntheticTrainingData,
            boolean guaranteed) {
    }
}
