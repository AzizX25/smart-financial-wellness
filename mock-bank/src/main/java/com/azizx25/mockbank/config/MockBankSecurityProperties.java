package com.azizx25.mockbank.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record MockBankSecurityProperties(
        @NotNull @Valid Jwt jwt,
        @Min(4) @Max(31) int bcryptStrength,
        @NotBlank String adminUiOrigin) {

    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration adminAccessTokenTtl) {

        @AssertTrue(message = "mock-bank access-token TTL must be greater than zero")
        public boolean isAccessTokenTtlValid() {
            return accessTokenTtl != null
                    && !accessTokenTtl.isZero()
                    && !accessTokenTtl.isNegative()
                    && adminAccessTokenTtl != null
                    && !adminAccessTokenTtl.isZero()
                    && !adminAccessTokenTtl.isNegative();
        }
    }
}
