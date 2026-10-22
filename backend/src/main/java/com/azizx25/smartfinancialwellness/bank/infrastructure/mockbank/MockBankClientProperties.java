package com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.integrations.mock-bank")
public record MockBankClientProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    @AssertTrue(message = "mock-bank base URL must be an absolute HTTP or HTTPS URL without credentials, query, or fragment")
    public boolean isBaseUrlValid() {
        if (baseUrl == null || !baseUrl.isAbsolute() || baseUrl.getHost() == null) {
            return false;
        }
        String scheme = baseUrl.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && baseUrl.getUserInfo() == null
                && baseUrl.getQuery() == null
                && baseUrl.getFragment() == null;
    }

    @AssertTrue(message = "mock-bank connection timeout must be greater than zero")
    public boolean isConnectTimeoutValid() {
        return connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative();
    }

    @AssertTrue(message = "mock-bank read timeout must be greater than zero")
    public boolean isReadTimeoutValid() {
        return readTimeout != null && !readTimeout.isZero() && !readTimeout.isNegative();
    }
}
