package com.azizx25.smartfinancialwellness.assistance;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.integrations.ai-assistance")
public record AssistanceProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout,
        boolean explainerEnabled, boolean visionEnabled) {
    public AssistanceProperties {
        if (baseUrl == null || !baseUrl.isAbsolute() || baseUrl.getHost() == null
                || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("AI assistance URL must be an HTTP(S) service URL");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || connectTimeout.compareTo(Duration.ofSeconds(10)) > 0 || readTimeout == null
                || readTimeout.isNegative() || readTimeout.isZero() || readTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("AI assistance timeouts must be positive and bounded");
        }
    }
}
