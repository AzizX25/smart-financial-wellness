package com.azizx25.smartfinancialwellness.category.infrastructure;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.integrations.ai-merchant")
public record MerchantAiClientProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotBlank String expectedModelId,
        @NotBlank String expectedModelVersion,
        @NotBlank String expectedFeatureVersion,
        @NotBlank String expectedEnrichmentProviderId,
        @NotBlank String expectedEnrichmentModelId,
        @NotBlank String expectedEnrichmentModelVersion,
        @NotBlank String expectedEnrichmentFeatureVersion,
        @NotBlank String expectedCatalogVersion,
        @NotBlank String expectedTaxonomyVersion) {
}
