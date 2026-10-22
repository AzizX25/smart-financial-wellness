package com.azizx25.mockbank.demo.api;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SupervisorDemoDtos {

    private SupervisorDemoDtos() {
    }

    public record SeedRequest(
            @NotBlank @Size(max = 60) String scenarioVersion,
            @NotNull LocalDate asOfDate,
            @NotBlank @Size(min = 8, max = 200) String customerPassword) {

        @Override
        public String toString() {
            return "SeedRequest[scenarioVersion=" + scenarioVersion
                    + ", asOfDate=" + asOfDate + ", customerPassword=<redacted>]";
        }
    }

    public record SeedResponse(
            String scenarioVersion,
            LocalDate asOfDate,
            String customerId,
            String username,
            String currentAccountId,
            String savingsAccountId,
            int accountCount,
            int transactionCount,
            int createdTransactionCount,
            int completedMonthCount,
            int pendingTransactionCount,
            BigDecimal currentAccountBalance,
            BigDecimal currentAccountAvailableBalance,
            BigDecimal savingsAccountBalance) {
    }

    public record ResetRequest(
            @NotBlank @Size(max = 60) String scenarioVersion,
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(max = 80) String confirmation) {
    }

    public record ResetResponse(
            String scenarioVersion,
            String username,
            boolean removed,
            int removedAccountCount,
            int removedTransactionCount) {
    }
}
