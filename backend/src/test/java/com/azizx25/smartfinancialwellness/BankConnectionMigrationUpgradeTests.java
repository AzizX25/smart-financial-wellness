package com.azizx25.smartfinancialwellness;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankConnectionMigrationUpgradeTests {

    private static final String SCHEMA = "smart_financial_wellness";
    private static final String MIGRATIONS = "classpath:db/migration";

    @Test
    void v11ThroughV17UpgradeExistingRowsAndAlsoMigrateAnEmptyDatabase() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("bank_connection_upgrade_test")
                .withUsername("test_user")
                .withPassword("test_password")) {
            postgres.start();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword()));

            Flyway throughV10 = flyway(postgres, MigrationVersion.fromVersion("10"));
            assertThat(throughV10.migrate().migrationsExecuted).isEqualTo(10);

            UUID userId = UUID.randomUUID();
            UUID connectionId = UUID.randomUUID();
            Instant expectedDisconnectedAt = Instant.parse("2026-01-15T12:30:00Z");
            jdbc.update(
                    "INSERT INTO smart_financial_wellness.app_user "
                            + "(id, display_name, status) VALUES (?, ?, 'ACTIVE')",
                    userId,
                    "Upgrade test user");
            jdbc.update(
                    "INSERT INTO smart_financial_wellness.bank_connection "
                            + "(id, user_id, provider_code, external_customer_id, status, "
                            + "created_at, updated_at) VALUES (?, ?, 'MOCK_BANK', NULL, "
                            + "'DISCONNECTED', ?, ?)",
                    connectionId,
                    userId,
                    Timestamp.from(expectedDisconnectedAt.minusSeconds(60)),
                    Timestamp.from(expectedDisconnectedAt));

            Flyway throughV11 = flyway(postgres, MigrationVersion.fromVersion("11"));
            assertThat(throughV11.migrate().migrationsExecuted).isEqualTo(1);
            Instant actualDisconnectedAt = jdbc.queryForObject(
                    "SELECT disconnected_at FROM smart_financial_wellness.bank_connection WHERE id = ?",
                    (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
                    connectionId);
            assertThat(actualDisconnectedAt).isEqualTo(expectedDisconnectedAt);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE smart_financial_wellness.bank_connection "
                            + "SET disconnected_at = NULL WHERE id = ?",
                    connectionId))
                    .isInstanceOf(DataAccessException.class);

            UUID accountId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO smart_financial_wellness.financial_account "
                            + "(id, user_id, bank_connection_id, account_origin, external_account_id, "
                            + "name, masked_identifier, account_type, currency, current_balance, status) "
                            + "VALUES (?, ?, ?, 'BANK_CONNECTION', 'UPGRADE-ACC-001', "
                            + "'Upgrade account', '****0001', 'CHECKING', 'TND', 10, 'DISCONNECTED')",
                    accountId,
                    userId,
                    connectionId);

            Flyway latest = flyway(postgres, null);
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);
            String upgradedAccount = jdbc.queryForObject(
                    "SELECT provider_code || '|' || COALESCE(provider_account_status, 'NULL') "
                            + "FROM smart_financial_wellness.financial_account WHERE id = ?",
                    String.class,
                    accountId);
            String synchronizationState = jdbc.queryForObject(
                    "SELECT sync_status FROM smart_financial_wellness.bank_connection WHERE id = ?",
                    String.class,
                    connectionId);
            assertThat(upgradedAccount).isEqualTo("MOCK_BANK|NULL");
            assertThat(synchronizationState).isEqualTo("IDLE");

            jdbc.update(
                    "UPDATE smart_financial_wellness.financial_account "
                            + "SET first_synchronized_at = CURRENT_TIMESTAMP, "
                            + "last_synchronized_at = CURRENT_TIMESTAMP WHERE id = ?",
                    accountId);
            UUID secondConnectionId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO smart_financial_wellness.bank_connection "
                            + "(id, user_id, provider_code, status, disconnected_at) "
                            + "VALUES (?, ?, 'MOCK_BANK', 'DISCONNECTED', CURRENT_TIMESTAMP)",
                    secondConnectionId,
                    userId);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO smart_financial_wellness.financial_account "
                            + "(id, user_id, bank_connection_id, account_origin, provider_code, "
                            + "external_account_id, name, masked_identifier, account_type, currency, "
                            + "current_balance, status, first_synchronized_at, last_synchronized_at) "
                            + "VALUES (?, ?, ?, 'BANK_CONNECTION', 'MOCK_BANK', 'UPGRADE-ACC-001', "
                            + "'Duplicate account', '****0001', 'CHECKING', 'TND', 10, 'DISCONNECTED', "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    UUID.randomUUID(),
                    userId,
                    secondConnectionId))
                    .isInstanceOf(DataAccessException.class);

            jdbc.execute("DROP SCHEMA smart_financial_wellness CASCADE");
            assertThat(flyway(postgres, null).migrate().migrationsExecuted).isEqualTo(17);
            Integer successfulMigrations = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM smart_financial_wellness.flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success",
                    Integer.class);
            assertThat(successfulMigrations).isEqualTo(17);
        }
    }

    private Flyway flyway(PostgreSQLContainer postgres, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations(MIGRATIONS);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
