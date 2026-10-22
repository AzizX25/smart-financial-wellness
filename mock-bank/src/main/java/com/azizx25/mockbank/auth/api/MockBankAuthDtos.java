package com.azizx25.mockbank.auth.api;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MockBankAuthDtos {

    private MockBankAuthDtos() {
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
            String customerId) {

        @Override
        public String toString() {
            return "LoginResponse[accessToken=<redacted>, tokenType=" + tokenType
                    + ", expiresAt=" + expiresAt + ", customerId=<redacted>]";
        }
    }
}
