package com.azizx25.smartfinancialwellness.salary.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SalaryPlanDtos {

    private SalaryPlanDtos() {
    }

    public record SalaryPlanPutRequest(
            @NotNull List<@Valid IncomeRequest> incomes,
            @NotNull List<@Valid AllocationOverrideRequest> allocationOverrides) {
    }

    public record IncomeRequest(
            @NotBlank @Size(max = 160) String label,
            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal expectedAmount) {
    }

    public record AllocationOverrideRequest(
            @NotNull UUID categoryId,
            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal confirmedAmount) {
    }

    public record IncomeResponse(String label, BigDecimal expectedAmount) {
    }

    public record AllocationResponse(
            UUID categoryId,
            String categoryName,
            BigDecimal recommendedAmount,
            BigDecimal confirmedAmount,
            BigDecimal effectiveAmount) {
    }

    public record RecommendationMetadataResponse(
            boolean metadataAvailable,
            LocalDate periodStart,
            LocalDate periodEndInclusive,
            Integer usableMonthCount,
            String method,
            String methodVersion,
            String fallbackReason) {
    }

    public record SalaryPlanResponse(
            String month,
            String currency,
            List<IncomeResponse> incomes,
            List<AllocationResponse> allocations,
            BigDecimal totalIncome,
            BigDecimal totalExpenses,
            BigDecimal savingCapacity,
            BigDecimal deficit,
            RecommendationMetadataResponse recommendationMetadata,
            Instant createdAt,
            Instant updatedAt,
            int version) {

        public SalaryPlanResponse {
            incomes = List.copyOf(incomes);
            allocations = List.copyOf(allocations);
        }
    }

    public record SalaryPlanPageResponse(
            List<SalaryPlanResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public SalaryPlanPageResponse {
            items = List.copyOf(items);
        }
    }

    public record AllocationProposalResponse(
            UUID categoryId,
            String categoryName,
            BigDecimal recommendedAmount) {
    }

    public record SalaryPlanProposalResponse(
            String month,
            String currency,
            List<AllocationProposalResponse> allocations,
            BigDecimal totalRecommendedExpenses,
            RecommendationMetadataResponse recommendationMetadata) {

        public SalaryPlanProposalResponse {
            allocations = List.copyOf(allocations);
        }
    }
}
