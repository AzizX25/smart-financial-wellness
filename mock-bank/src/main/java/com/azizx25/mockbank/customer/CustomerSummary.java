package com.azizx25.mockbank.customer;

import java.time.OffsetDateTime;

public record CustomerSummary(
        String externalCustomerId,
        String displayName,
        String countryCode,
        String status,
        OffsetDateTime createdAt) {
}