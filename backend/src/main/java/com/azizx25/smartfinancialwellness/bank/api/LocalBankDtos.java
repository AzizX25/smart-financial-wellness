package com.azizx25.smartfinancialwellness.bank.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LocalBankDtos {

    private LocalBankDtos() {
    }

    public record LocalAccountResponse(
            UUID id,
            String provider,
            String displayName,
            String accountType,
            String maskedAccountReference,
            String currency,
            BigDecimal currentBalance,
            BigDecimal availableBalance,
            String providerStatus,
            String localStatus,
            Instant firstSynchronizedAt,
            Instant lastSynchronizedAt,
            String accountOrigin) {
    }

    public record LocalTransactionResponse(
            UUID id,
            UUID accountId,
            LocalDate bookingDate,
            LocalDate valueDate,
            String description,
            String counterpartyName,
            String direction,
            BigDecimal amount,
            String currency,
            String providerStatus,
            UUID categoryId,
            String categoryName,
            String categoryNature,
            String categoryAssignmentSource,
            Instant categorizedAt,
            Instant firstImportedAt,
            Instant lastSynchronizedAt,
            String sourceType,
            int version,
            String amountExact,
            boolean localOverride,
            boolean categoryApproved) {
    }

    public record LocalTransactionPageResponse(
            List<LocalTransactionResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {

        public LocalTransactionPageResponse {
            items = List.copyOf(items);
        }
    }

    public record DashboardSummaryResponse(
            String month,
            List<AccountBalanceTotalResponse> balanceTotals,
            List<MonthlyCashFlowResponse> monthlyCashFlows,
            List<MonthlyCategorySpendingResponse> categorySpending) {

        public DashboardSummaryResponse {
            balanceTotals = List.copyOf(balanceTotals);
            monthlyCashFlows = List.copyOf(monthlyCashFlows);
            categorySpending = List.copyOf(categorySpending);
        }
    }

    public record AccountBalanceTotalResponse(
            String currency,
            BigDecimal totalBalance,
            long accountCount) {
    }

    public record MonthlyCashFlowResponse(
            String currency,
            BigDecimal income,
            BigDecimal expenses,
            BigDecimal netCashFlow) {
    }

    public record MonthlyCategorySpendingResponse(
            String currency,
            BigDecimal totalExpenses,
            List<CategoryExpenseResponse> categories,
            long uncategorizedCount,
            BigDecimal uncategorizedAmount) {

        public MonthlyCategorySpendingResponse {
            categories = List.copyOf(categories);
        }
    }

    public record CategoryExpenseResponse(
            UUID categoryId,
            String categoryName,
            long transactionCount,
            BigDecimal totalAmount) {
    }
}
