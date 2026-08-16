package com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

final class MockBankHttpDtos {

    private MockBankHttpDtos() {
    }

    record LoginRequest(String username, String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=<redacted>]";
        }
    }

    record LoginResponse(
            String accessToken,
            String tokenType,
            OffsetDateTime expiresAt,
            String customerId) {
    }

    record AccountResponse(
            String externalAccountId,
            String displayName,
            String accountType,
            String currency,
            String maskedAccountReference,
            String status,
            OffsetDateTime createdAt) {
    }

    record BalanceResponse(
            String externalAccountId,
            String currency,
            BigDecimal currentBalance,
            BigDecimal availableBalance,
            String status,
            String maskedAccountReference) {
    }

    record TransactionResponse(
            String externalTransactionId,
            LocalDate bookingDate,
            LocalDate valueDate,
            BigDecimal amount,
            String direction,
            String currency,
            String description,
            String counterpartyName,
            String status) {
    }

    record TransactionPageResponse(
            List<TransactionResponse> items,
            Integer page,
            Integer size,
            Long totalElements,
            Integer totalPages,
            Boolean first,
            Boolean last) {
    }

    record ErrorResponse(
            Integer status,
            String error,
            String message,
            String path) {
    }
}
