package com.azizx25.mockbank.account;

import java.time.OffsetDateTime;

public record AccountSummary(
        String externalAccountId,
        String displayName,
        String accountType,
        String currency,
        String maskedAccountReference,
        String status,
        OffsetDateTime createdAt) {
}
