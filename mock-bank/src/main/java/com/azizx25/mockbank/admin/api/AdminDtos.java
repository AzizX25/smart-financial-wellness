package com.azizx25.mockbank.admin.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=<redacted>]";
        }
    }

    public record LoginResponse(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            String username) {

        @Override
        public String toString() {
            return "LoginResponse[accessToken=<redacted>, tokenType=" + tokenType
                    + ", expiresAt=" + expiresAt + ", username=" + username + "]";
        }
    }

    public record PageResponse<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {
    }

    public record CustomerResponse(
            String customerId,
            String displayName,
            String username,
            String countryCode,
            String status,
            OffsetDateTime createdAt) {
    }

    public record CreateCustomerRequest(
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 80)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
            @NotBlank @Size(min = 8, max = 200) String password,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode) {

        @Override
        public String toString() {
            return "CreateCustomerRequest[displayName=" + displayName
                    + ", username=" + username + ", password=<redacted>, countryCode="
                    + countryCode + "]";
        }
    }

    public record UpdateCustomerRequest(
            @Size(min = 1, max = 160) String displayName,
            @Size(min = 1, max = 80)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
            @Pattern(regexp = "[A-Za-z]{2}") String countryCode) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 8, max = 200) String password) {

        @Override
        public String toString() {
            return "ResetPasswordRequest[password=<redacted>]";
        }
    }

    public record AccountResponse(
            String accountId,
            String customerId,
            String displayName,
            String accountType,
            String currency,
            BigDecimal currentBalance,
            BigDecimal availableBalance,
            String status,
            OffsetDateTime createdAt) {
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank
            @Pattern(regexp = "CHECKING|SAVINGS|CASH|OTHER") String accountType,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency) {
    }

    public record UpdateAccountRequest(
            @Size(min = 1, max = 160) String displayName,
            @Pattern(regexp = "CHECKING|SAVINGS|CASH|OTHER") String accountType) {
    }

    public record CreateTransactionRequest(
            @NotNull
            @DecimalMin(value = "0.0001")
            @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotBlank @Pattern(regexp = "CREDIT|DEBIT") String direction,
            @NotBlank @Size(max = 500) String description,
            LocalDate bookingDate,
            @Size(max = 120) String reference,
            @Size(max = 160) String counterpartyName) {
    }

    public record TransactionResponse(
            String transactionId,
            String accountId,
            LocalDate bookingDate,
            LocalDate valueDate,
            BigDecimal amount,
            String direction,
            String currency,
            String description,
            String counterpartyName,
            String status,
            OffsetDateTime createdAt) {
    }

    public record DashboardResponse(
            long totalCustomers,
            long activeCustomers,
            long inactiveCustomers,
            long totalAccounts,
            long activeAccounts,
            long frozenAccounts,
            long closedAccounts,
            long totalTransactions,
            List<TransactionResponse> recentTransactions) {
    }
}
