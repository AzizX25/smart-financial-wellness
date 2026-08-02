package com.azizx25.smartfinancialwellness.bank.model;

import java.time.OffsetDateTime;

public record ExternalBankAccount(
        String externalAccountId,
        String displayName,
        String accountType,
        String currency,
        String maskedAccountReference,
        String status,
        OffsetDateTime createdAt) {
}
