package com.azizx25.smartfinancialwellness.config;

import java.net.URI;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.email")
public record EmailProperties(
        @NotBlank @Email String senderAddress,
        @NotBlank String senderName,
        @NotNull URI frontendBaseUrl) {
}
