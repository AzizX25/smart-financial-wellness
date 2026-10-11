package com.azizx25.smartfinancialwellness.score.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.Calculation;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.FactorResult;
import com.azizx25.smartfinancialwellness.score.persistence.EvaluationFactor;
import com.azizx25.smartfinancialwellness.score.persistence.WellnessEvaluation;

public final class FinancialScoreDtos {

    public static final String DISCLAIMER = "Cet indicateur est une estimation pédagogique de votre situation "
            + "financière. Il ne constitue ni un score de crédit ni un conseil financier.";

    private FinancialScoreDtos() {
    }

    public record FactorResponse(
            String code, int baseWeight, BigDecimal rawValue, String unit,
            BigDecimal score, boolean available, String explanation,
            String unavailabilityReason) {

        static FactorResponse from(FactorResult factor) {
            return new FactorResponse(factor.code().name(), factor.code().baseWeight(),
                    factor.rawValue(), factor.unit(), factor.score(), factor.available(),
                    factor.explanation(), factor.unavailabilityReason());
        }

        static FactorResponse from(EvaluationFactor factor) {
            return new FactorResponse(factor.getDimensionType().name(), factor.getBaseWeight(),
                    factor.getRawValue(), factor.getUnit(), factor.getScore(), factor.isAvailable(),
                    factor.getExplanation(), factor.getUnavailableReason());
        }
    }

    public record ScoreResponse(
            boolean available, String status, UUID id, String currency, LocalDate asOfDate,
            String timeZone, LocalDate historyStart, LocalDate historyEnd,
            String calculationVersion, BigDecimal score, String bandCode, String bandLabel,
            String bandDescription, int availableBaseWeight, List<FactorResponse> factors,
            Instant createdAt, String disclaimer) {
        public ScoreResponse { factors = List.copyOf(factors); }

        public static ScoreResponse insufficient(
                String currency, LocalDate asOfDate, String timeZone,
                LocalDate historyStart, LocalDate historyEnd,
                String version, Calculation calculation) {
            return new ScoreResponse(false, calculation.status(), null, currency, asOfDate,
                    timeZone, historyStart, historyEnd, version, null, null, null, null,
                    calculation.availableBaseWeight(),
                    calculation.factors().stream().map(FactorResponse::from).toList(),
                    null, DISCLAIMER);
        }

        public static ScoreResponse from(WellnessEvaluation evaluation) {
            String code = evaluation.getLevel().name();
            return new ScoreResponse(true, "AVAILABLE", evaluation.getId(), evaluation.getCurrency(),
                    evaluation.getAsOfDate(), evaluation.getTimeZone(), evaluation.getPeriodStart(),
                    evaluation.getPeriodEnd(), evaluation.getMethodVersion(),
                    evaluation.getGlobalScore(), code, evaluation.getLevel().frenchLabel(),
                    bandDescription(code), evaluation.getAvailableBaseWeight(),
                    evaluation.getFactors().stream()
                            .sorted(java.util.Comparator.comparingInt(
                                    factor -> factor.getDimensionType().ordinal()))
                            .map(FactorResponse::from).toList(),
                    evaluation.getCreatedAt(), DISCLAIMER);
        }

        private static String bandDescription(String code) {
            return switch (code) {
                case "A_RENFORCER" -> "L'indicateur synthétique se situe entre 0 et 40.";
                case "FRAGILE" -> "L'indicateur synthétique se situe entre 40 et 60.";
                case "EN_PROGRESSION" -> "L'indicateur synthétique se situe entre 60 et 80.";
                case "SOLIDE" -> "L'indicateur synthétique se situe entre 80 et 100.";
                default -> throw new IllegalStateException("Unsupported score band: " + code);
            };
        }
    }

    public record ScorePageResponse(
            List<ScoreResponse> items, int page, int size, long totalElements, int totalPages) {
        public ScorePageResponse { items = List.copyOf(items); }
    }
}
