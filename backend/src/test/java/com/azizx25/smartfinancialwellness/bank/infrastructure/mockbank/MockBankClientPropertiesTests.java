package com.azizx25.smartfinancialwellness.bank.infrastructure.mockbank;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class MockBankClientPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsValidProperties() {
        contextRunner.withPropertyValues(
                        "app.integrations.mock-bank.base-url=https://bank.example.test/provider",
                        "app.integrations.mock-bank.connect-timeout=750ms",
                        "app.integrations.mock-bank.read-timeout=4s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockBankClientProperties properties = context.getBean(MockBankClientProperties.class);
                    assertThat(properties.baseUrl())
                            .isEqualTo(URI.create("https://bank.example.test/provider"));
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(750));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(4));
                });
    }

    @Test
    void rejectsMalformedBaseUrl() {
        runInvalidConfiguration("app.integrations.mock-bank.base-url=not a URI");
    }

    @Test
    void rejectsUnsupportedBaseUrlScheme() {
        runInvalidConfiguration("app.integrations.mock-bank.base-url=ftp://bank.example.test");
    }

    @Test
    void rejectsNonPositiveConnectTimeout() {
        runInvalidConfiguration("app.integrations.mock-bank.connect-timeout=0s");
    }

    @Test
    void rejectsNonPositiveReadTimeout() {
        runInvalidConfiguration("app.integrations.mock-bank.read-timeout=-1s");
    }

    private void runInvalidConfiguration(String invalidProperty) {
        contextRunner.withPropertyValues(
                        "app.integrations.mock-bank.base-url=http://localhost:8081",
                        "app.integrations.mock-bank.connect-timeout=1s",
                        "app.integrations.mock-bank.read-timeout=2s",
                        invalidProperty)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("mock-bank");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MockBankClientProperties.class)
    static class PropertiesConfiguration {
    }
}
