package com.azizx25.smartfinancialwellness.bank.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class BankDtos {

    private BankDtos() {
    }

    public record ConnectMockBankRequest(
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "ConnectMockBankRequest[username=" + username + ", password=<redacted>]";
        }
    }

    public record BankConnectionResponse(
            UUID id,
            String providerCode,
            String status,
            boolean reconnectRequired,
            Instant connectedAt,
            Instant tokenExpiresAt,
            Instant disconnectedAt) {
    }

    public record BankAccountResponse(
            UUID id,
            String displayName,
            String accountType,
            String currency,
            String maskedAccountReference,
            String status,
            Instant createdAt) {
    }

    public record BankBalanceResponse(
            UUID accountId,
            String currency,
            BigDecimal currentBalance,
            BigDecimal availableBalance,
            String status,
            String maskedAccountReference) {
    }

    public record BankTransactionResponse(
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

    public record BankTransactionPageResponse(
            List<BankTransactionResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {

        public BankTransactionPageResponse {
            items = List.copyOf(items);
        }
    }
}
