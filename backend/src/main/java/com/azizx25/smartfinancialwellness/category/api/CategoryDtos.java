package com.azizx25.smartfinancialwellness.category.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.persistence.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CategoryDtos {

    private CategoryDtos() {
    }

    public record CategoryRequest(
            @NotBlank @Size(max = 160) String name,
            @NotNull Category.Nature nature,
            @Size(max = 40) @Pattern(regexp = "[a-z0-9-]+") String iconKey,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color) {
    }

    public record CategoryResponse(
            UUID id,
            String code,
            String name,
            String nature,
            String iconKey,
            String color,
            boolean system,
            boolean active,
            Instant createdAt,
            Instant updatedAt) {

        public static CategoryResponse from(Category category) {
            return new CategoryResponse(
                    category.getId(),
                    category.getCode(),
                    category.getLabel(),
                    category.getNature().name(),
                    category.getIconKey(),
                    category.getColor(),
                    category.getUserId() == null,
                    category.isActive(),
                    category.getCreatedAt(),
                    category.getUpdatedAt());
        }
    }

    public record TransactionCategoryRequest(UUID categoryId) {
    }

    public record TransactionCategoryResponse(
            UUID transactionId,
            UUID categoryId,
            String categoryName,
            String categoryNature,
            String assignmentSource,
            Instant categorizedAt) {
    }

    public record CategorizationRunResponse(int categorizedCount, long uncategorizedCount) {
    }

    public record MerchantRecognitionRunResponse(
            int analyzedCount,
            int suggestedCount,
            int unrecognizedCount,
            int unsupportedCurrencyCount,
            int reviewRequiredCount,
            int excludedCount,
            String outcome) {
    }

    public record MerchantSuggestionResponse(
            UUID predictionId,
            UUID transactionId,
            UUID categoryId,
            String categoryCode,
            String categoryName,
            String canonicalMerchant,
            String matchMethod,
            String source,
            String rationale,
            List<String> evidenceUrls,
            boolean experimental,
            boolean guaranteed,
            Instant producedAt) {
        public MerchantSuggestionResponse {
            evidenceUrls = List.copyOf(evidenceUrls);
        }
    }

    public record ReviewCandidateResponse(
            int rank,
            UUID categoryId,
            String categoryCode,
            String categoryName) {
    }

    public record CategorizationReviewResponse(
            UUID reviewId,
            UUID transactionId,
            LocalDate bookingDate,
            String description,
            String counterpartyName,
            String direction,
            BigDecimal amount,
            String currency,
            String providerStatus,
            String reviewStatus,
            String reasonCode,
            MerchantSuggestionResponse pendingSuggestion,
            List<ReviewCandidateResponse> candidateCategories,
            boolean manualCategorizationAllowed) {

        public CategorizationReviewResponse {
            candidateCategories = List.copyOf(candidateCategories);
        }
    }

    public record CategorizationReviewPageResponse(
            List<CategorizationReviewResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {

        public CategorizationReviewPageResponse {
            items = List.copyOf(items);
        }
    }

    public record MerchantMemoryResponse(
            UUID id,
            String normalizedMerchant,
            String direction,
            String currency,
            UUID categoryId,
            String categoryName,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CategorySummaryResponse(String month, List<CurrencyCategorySummary> currencies) {
        public CategorySummaryResponse { currencies = List.copyOf(currencies); }
    }

    public record CurrencyCategorySummary(
            String currency,
            List<CategoryTotal> categories,
            long uncategorizedCount,
            BigDecimal uncategorizedAmount) {
        public CurrencyCategorySummary { categories = List.copyOf(categories); }
    }

    public record CategoryTotal(
            UUID categoryId,
            String categoryName,
            String nature,
            long transactionCount,
            BigDecimal totalAmount) {
    }
}
