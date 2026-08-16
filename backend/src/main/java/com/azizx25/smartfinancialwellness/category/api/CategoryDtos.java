package com.azizx25.smartfinancialwellness.category.api;

import java.math.BigDecimal;
import java.time.Instant;
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
