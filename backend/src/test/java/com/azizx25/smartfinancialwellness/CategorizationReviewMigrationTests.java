package com.azizx25.smartfinancialwellness;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CategorizationReviewMigrationTests {

    private static final String SCHEMA = "smart_financial_wellness";
    private static final UUID UNCATEGORIZED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID SHOPPING = UUID.fromString("00000000-0000-0000-0000-000000000013");

    @Test
    void upgradesV19ToV21AndEnforcesReviewMemoryAndExistingEvidenceIntegrity() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("categorization_review_upgrade_test")
                .withUsername("test_user")
                .withPassword("test_password")) {
            postgres.start();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

            Flyway throughV19 = flyway(postgres, MigrationVersion.fromVersion("19"));
            assertThat(throughV19.migrate().migrationsExecuted).isEqualTo(19);
            Integer v19Checksum = jdbc.queryForObject(
                    "SELECT checksum FROM smart_financial_wellness.flyway_schema_history WHERE version = '19'",
                    Integer.class);

            Flyway latest = flyway(postgres, null);
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
            latest.validate();
            assertThat(jdbc.queryForObject(
                    "SELECT version FROM smart_financial_wellness.flyway_schema_history "
                            + "WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
                    String.class)).isEqualTo("21");
            assertThat(jdbc.queryForObject(
                    "SELECT checksum FROM smart_financial_wellness.flyway_schema_history WHERE version = '19'",
                    Integer.class)).isEqualTo(v19Checksum);

            UUID owner = createUser(jdbc, "Review owner");
            UUID foreignUser = createUser(jdbc, "Foreign owner");
            UUID account = createAccount(jdbc, owner);
            UUID transaction = createTransaction(jdbc, owner, account);
            UUID review = UUID.randomUUID();
            jdbc.update("INSERT INTO smart_financial_wellness.categorization_review "
                            + "(id, transaction_id, user_id, state, reason_code) "
                            + "VALUES (?, ?, ?, 'NEEDS_REVIEW', 'MODEL_ABSTAINED')",
                    review, transaction, owner);
            jdbc.update("INSERT INTO smart_financial_wellness.categorization_review_candidate "
                            + "(id, review_id, category_id, candidate_rank) VALUES (?, ?, ?, 1)",
                    UUID.randomUUID(), review, FOOD);
            jdbc.update("INSERT INTO smart_financial_wellness.categorization_review_candidate "
                            + "(id, review_id, category_id, candidate_rank) VALUES (?, ?, ?, 2)",
                    UUID.randomUUID(), review, SHOPPING);

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO smart_financial_wellness.categorization_review_candidate "
                            + "(id, review_id, category_id, candidate_rank) VALUES (?, ?, ?, 4)",
                    UUID.randomUUID(), review, FOOD)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO smart_financial_wellness.categorization_review_candidate "
                            + "(id, review_id, category_id, candidate_rank) VALUES (?, ?, ?, 2)",
                    UUID.randomUUID(), review, FOOD)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO smart_financial_wellness.categorization_review "
                            + "(id, transaction_id, user_id, state, reason_code) "
                            + "VALUES (?, ?, ?, 'NEEDS_REVIEW', 'NOT_ANALYZED')",
                    UUID.randomUUID(), transaction, owner)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO smart_financial_wellness.categorization_review "
                            + "(id, transaction_id, user_id, state, reason_code) "
                            + "VALUES (?, ?, ?, 'NEEDS_REVIEW', 'NOT_ANALYZED')",
                    UUID.randomUUID(), transaction, foreignUser)).isInstanceOf(DataAccessException.class);

            UUID foreignCategory = createPrivateCategory(jdbc, foreignUser);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO smart_financial_wellness.user_merchant_memory "
                            + "(id, user_id, normalized_merchant_key, direction, currency, category_id) "
                            + "VALUES (?, ?, 'private merchant', 'DEBIT', 'TND', ?)",
                    UUID.randomUUID(), owner, foreignCategory)).isInstanceOf(DataAccessException.class);

            UUID memory = UUID.randomUUID();
            jdbc.update("INSERT INTO smart_financial_wellness.user_merchant_memory "
                            + "(id, user_id, normalized_merchant_key, direction, currency, category_id) "
                            + "VALUES (?, ?, 'private merchant', 'DEBIT', 'TND', ?)",
                    memory, owner, FOOD);
            jdbc.update("INSERT INTO smart_financial_wellness.user_merchant_memory "
                            + "(id, user_id, normalized_merchant_key, direction, currency, category_id) "
                            + "VALUES (?, ?, 'private merchant', 'DEBIT', 'TND', ?)",
                    UUID.randomUUID(), foreignUser, FOOD);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM smart_financial_wellness.user_merchant_memory "
                            + "WHERE normalized_merchant_key = 'private merchant'",
                    Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO smart_financial_wellness.user_merchant_memory "
                            + "(id, user_id, normalized_merchant_key, direction, currency, category_id) "
                            + "VALUES (?, ?, 'private merchant', 'DEBIT', 'TND', ?)",
                    UUID.randomUUID(), owner, SHOPPING)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE smart_financial_wellness.user_merchant_memory "
                            + "SET user_id = ? WHERE id = ?", foreignUser, memory))
                    .isInstanceOf(DataAccessException.class);

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE smart_financial_wellness.categorization_review "
                            + "SET state = 'RESOLVED', reason_code = 'MANUAL_ASSIGNMENT', "
                            + "resolution = 'MANUAL_ASSIGNMENT', resolved_category_id = ?, "
                            + "resolved_at = CURRENT_TIMESTAMP WHERE id = ?",
                    FOOD, review)).isInstanceOf(DataAccessException.class);

            UUID resolvedTransaction = createTransaction(jdbc, owner, account);
            jdbc.update("UPDATE smart_financial_wellness.transaction_record "
                            + "SET category_id = ?, category_source = 'USER', categorized_at = CURRENT_TIMESTAMP "
                            + "WHERE id = ?",
                    FOOD, resolvedTransaction);
            UUID resolvedReview = UUID.randomUUID();
            jdbc.update("INSERT INTO smart_financial_wellness.categorization_review "
                            + "(id, transaction_id, user_id, state, reason_code, resolution, "
                            + "resolved_category_id, resolved_at) "
                            + "VALUES (?, ?, ?, 'RESOLVED', 'MANUAL_ASSIGNMENT', 'MANUAL_ASSIGNMENT', ?, "
                            + "CURRENT_TIMESTAMP)",
                    resolvedReview, resolvedTransaction, owner, FOOD);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE smart_financial_wellness.categorization_review "
                            + "SET reason_code = 'CHANGED_RESOLUTION' WHERE id = ?", resolvedReview))
                    .isInstanceOf(DataAccessException.class);

            UUID prediction = UUID.randomUUID();
            jdbc.update("INSERT INTO smart_financial_wellness.category_prediction "
                            + "(id, transaction_id, category_id, prediction_method, model_id, model_version, "
                            + "prediction_score, explanation, canonical_merchant, feature_version, "
                            + "review_status, produced_at) "
                            + "VALUES (?, ?, ?, 'ML', 'model', 'v1', 0.9000, 'ML', 'Merchant', "
                            + "'features-v1', 'PENDING', CURRENT_TIMESTAMP)",
                    prediction, transaction, FOOD);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE smart_financial_wellness.category_prediction "
                            + "SET canonical_merchant = 'Changed' WHERE id = ?", prediction))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    private UUID createUser(JdbcTemplate jdbc, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO smart_financial_wellness.app_user (id, display_name, status) "
                + "VALUES (?, ?, 'ACTIVE')", id, name);
        return id;
    }

    private UUID createAccount(JdbcTemplate jdbc, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO smart_financial_wellness.financial_account "
                        + "(id, user_id, account_origin, name, account_type, currency, status) "
                        + "VALUES (?, ?, 'MANUAL', 'Review account', 'OTHER', 'TND', 'ACTIVE')",
                id, userId);
        return id;
    }

    private UUID createTransaction(JdbcTemplate jdbc, UUID userId, UUID accountId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO smart_financial_wellness.transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, "
                        + "client_idempotency_key, booking_date, amount, direction, currency, "
                        + "raw_description, normalized_description, category_source) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, 10.0000, 'DEBIT', 'TND', "
                        + "'Public merchant', 'public merchant', 'UNCATEGORIZED')",
                id, userId, accountId, UNCATEGORIZED, UUID.randomUUID().toString(), LocalDate.of(2026, 8, 20));
        return id;
    }

    private UUID createPrivateCategory(JdbcTemplate jdbc, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO smart_financial_wellness.category "
                        + "(id, user_id, code, label, taxonomy_version, direction, nature, active) "
                        + "VALUES (?, ?, ?, 'Private', 'user-v1', 'DEBIT', 'EXPENSE', TRUE)",
                id, userId, "PERSONAL_" + id.toString().replace("-", "").toUpperCase());
        return id;
    }

    private Flyway flyway(PostgreSQLContainer postgres, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
