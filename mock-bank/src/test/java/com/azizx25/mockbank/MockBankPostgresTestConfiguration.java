package com.azizx25.mockbank;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class MockBankPostgresTestConfiguration {

    private static final String TEST_JWT_SECRET =
            Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(64));

    @Bean
    @DependsOn("entityManagerFactory")
    InitializingBean isolatedAuthenticationFixtures(JdbcTemplate jdbc, PasswordEncoder encoder) {
        return () -> {
            // Only an isolated Testcontainers database receives these opt-in fixtures.
            jdbc.update("UPDATE mock_bank.bank_customer SET status='ACTIVE', password_hash=? "
                    + "WHERE external_customer_id IN ('MOCK-CUST-001','MOCK-CUST-002')",
                    encoder.encode(TestBankCredentials.CUSTOMER_PASSWORD));
            jdbc.update("INSERT INTO mock_bank.bank_administrator (id, username, password_hash, status) "
                    + "VALUES (?, ?, ?, 'ACTIVE')", UUID.randomUUID(), TestBankCredentials.ADMIN_USERNAME,
                    encoder.encode(TestBankCredentials.ADMIN_PASSWORD));
        };
    }

    @Bean
    static DynamicPropertyRegistrar testProperties() {
        return registry -> {
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
            registry.add("spring.datasource.password", () -> "test_password");
            registry.add("server.port", () -> 0);
            registry.add("app.security.jwt.secret", () -> TEST_JWT_SECRET);
            registry.add("app.security.bcrypt-strength", () -> 4);
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
