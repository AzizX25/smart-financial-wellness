package com.azizx25.smartfinancialwellness.recommendation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public final class RecommendationDtos {
    public static final String DISCLAIMER = "Ces pistes sont informatives, sans garantie de résultat. "
            + "Elles ne constituent pas un conseil financier personnalisé réglementé.";

    private RecommendationDtos() { }

    public enum FeedbackStatus { ACCEPTED, REJECTED, HIDDEN }
    public record StatusRequest(@NotNull FeedbackStatus status) { }
    public record Fact(String code, BigDecimal value, String unit, BigDecimal factorScore,
            String explanation) { }
    public record RecommendationResponse(UUID id, UUID evaluationId, String type, String priority,
            String status, String title, String message, String currency,
            LocalDate periodStart, LocalDate periodEnd, String rulesVersion, Fact fact,
            String actionPath, String actionLabel, String disclaimer, Instant createdAt) { }
    public record GenerationResponse(String status, String currency, UUID evaluationId,
            String rulesVersion, List<RecommendationResponse> items) {
        public GenerationResponse { items = List.copyOf(items); }
    }
    public record PageResponse(List<RecommendationResponse> items, int page, int size,
            long totalElements, int totalPages) {
        public PageResponse { items = List.copyOf(items); }
    }
}
