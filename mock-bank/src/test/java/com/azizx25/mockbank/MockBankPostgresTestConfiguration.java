package com.azizx25.mockbank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class MockBankPostgresTestConfiguration {

    @Bean
    static DynamicPropertyRegistrar testProperties() {
        return registry -> {
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
            registry.add("spring.datasource.password", () -> "test_password");
            registry.add("server.port", () -> 0);
        };
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("mock_bank_test")
                .withUsername("test_user")
                .withPassword("test_password");
    }
}
