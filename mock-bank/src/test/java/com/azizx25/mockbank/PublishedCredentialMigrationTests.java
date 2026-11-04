package com.azizx25.mockbank;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Deliberately excludes the opt-in Spring authentication test fixtures. */
class PublishedCredentialMigrationTests {
    private PostgreSQLContainer database() {
        var database = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("credential_upgrade_test")
                .withUsername("test_user")
                .withPassword(TestBankCredentials.randomSecret());
        database.start();
        return database;
    }

    private Flyway migrations(PostgreSQLContainer database, String version) {
        return Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .defaultSchema("mock_bank").schemas("mock_bank")
                .target(MigrationVersion.fromVersion(version)).load();
    }

    private JdbcTemplate jdbc(PostgreSQLContainer database) {
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }

    @Test
    void freshInstallationHasNoActiveKnownPasswordAccountsAndPreservesLedger() {
        try (var database = database()) {
            var flyway = migrations(database, "5");
            flyway.migrate();
            flyway.validate();
            var jdbc = jdbc(database);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_bank.bank_customer WHERE status='ACTIVE'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_bank.bank_administrator WHERE status='ACTIVE'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_bank.bank_customer", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_bank.bank_account", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_bank.bank_transaction", Integer.class)).isEqualTo(8);
        }
    }

    @Test
    void upgradeReplacesPublishedHashesAndLeavesOrdinaryAndRecredentialedAccountsUntouched() {
        try (var database = database()) {
            migrations(database, "4").migrate();
            var jdbc = jdbc(database);
            String oldCustomerHash = jdbc.queryForObject(
                    "SELECT password_hash FROM mock_bank.bank_customer WHERE external_customer_id='MOCK-CUST-001'", String.class);
            String oldAdminHash = jdbc.queryForObject(
                    "SELECT password_hash FROM mock_bank.bank_administrator WHERE username='admin.demo'", String.class);
            String changedHash = new BCryptPasswordEncoder(4).encode(TestBankCredentials.randomSecret());
            jdbc.update("UPDATE mock_bank.bank_customer SET password_hash=?, username='changed.customer' WHERE external_customer_id='MOCK-CUST-002'", changedHash);
            jdbc.update("INSERT INTO mock_bank.bank_customer (id, external_customer_id, display_name, country_code, username, password_hash) "
                    + "VALUES (?, 'ORDINARY-CUSTOMER', 'Synthetic isolation fixture', 'TN', 'ordinary.customer', ?)", UUID.randomUUID(), oldCustomerHash);
            jdbc.update("INSERT INTO mock_bank.bank_administrator (id, username, password_hash) VALUES (?, 'ordinary.admin', ?)", UUID.randomUUID(), oldAdminHash);
            var balances = jdbc.queryForList("SELECT id, current_balance, available_balance FROM mock_bank.bank_account ORDER BY id");

            migrations(database, "5").migrate();
            assertThat(jdbc.queryForObject("SELECT status FROM mock_bank.bank_customer WHERE external_customer_id='MOCK-CUST-001'", String.class)).isEqualTo("INACTIVE");
            assertThat(jdbc.queryForObject("SELECT status FROM mock_bank.bank_administrator WHERE username='admin.demo'", String.class)).isEqualTo("INACTIVE");
            assertThat(oldCustomerHash.equals(jdbc.queryForObject("SELECT password_hash FROM mock_bank.bank_customer WHERE external_customer_id='MOCK-CUST-001'", String.class))).isFalse();
            assertThat(oldAdminHash.equals(jdbc.queryForObject("SELECT password_hash FROM mock_bank.bank_administrator WHERE username='admin.demo'", String.class))).isFalse();
            assertThat(changedHash.equals(jdbc.queryForObject("SELECT password_hash FROM mock_bank.bank_customer WHERE external_customer_id='MOCK-CUST-002'", String.class))).isTrue();
            assertThat(jdbc.queryForList("SELECT status FROM mock_bank.bank_customer WHERE username IN ('changed.customer','ordinary.customer')", String.class)).containsOnly("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT status FROM mock_bank.bank_administrator WHERE username='ordinary.admin'", String.class)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForList("SELECT id, current_balance, available_balance FROM mock_bank.bank_account ORDER BY id")).isEqualTo(balances);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_bank.bank_transaction", Integer.class)).isEqualTo(8);
        }
    }

    @Test
    void upgradePreservesAdministratorWhosePasswordWasAlreadyChanged() {
        try (var database = database()) {
            migrations(database, "4").migrate();
            var jdbc = jdbc(database);
            String changedHash = new BCryptPasswordEncoder(4).encode(TestBankCredentials.randomSecret());
            jdbc.update("UPDATE mock_bank.bank_administrator SET username='renamed.admin', password_hash=? WHERE username='admin.demo'", changedHash);
            migrations(database, "5").migrate();
            assertThat(jdbc.queryForObject("SELECT status FROM mock_bank.bank_administrator WHERE username='renamed.admin'", String.class)).isEqualTo("ACTIVE");
            assertThat(changedHash.equals(jdbc.queryForObject("SELECT password_hash FROM mock_bank.bank_administrator WHERE username='renamed.admin'", String.class))).isTrue();
        }
    }
}
