package com.azizx25.mockbank.client;

import java.time.OffsetDateTime;

public record OAuthClientSummary(
        String clientIdentifier,
        String clientName,
        String redirectUri,
        OffsetDateTime createdAt) {
}
