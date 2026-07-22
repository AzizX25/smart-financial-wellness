package com.azizx25.smartfinancialwellness.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {

    private static final String TEST_JWT_SECRET =
            "c2Z3YS10ZXN0LW9ubHktMzItYnl0ZS1zZWNyZXQhISE=";

    @Bean
    static DynamicPropertyRegistrar testSecurityProperties() {
        return registry -> {
            registry.add("app.security.jwt.secret", () -> TEST_JWT_SECRET);
            registry.add("app.security.bcrypt-strength", () -> 4);
            registry.add("app.security.refresh-cookie-secure", () -> false);
        };
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("smart_financial_wellness_test")
                .withUsername("test_user")
                .withPassword("test_password");
    }
}
