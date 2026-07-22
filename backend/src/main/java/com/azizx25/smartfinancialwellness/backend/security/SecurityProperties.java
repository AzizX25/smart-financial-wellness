package com.azizx25.smartfinancialwellness.backend.security;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @NotNull @Valid Jwt jwt,
        @NotNull Duration refreshTokenTtl,
        boolean refreshCookieSecure,
        @Min(4) @Max(31) int bcryptStrength,
        @NotBlank String corsAllowedOrigin) {

    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl) {
    }
}
