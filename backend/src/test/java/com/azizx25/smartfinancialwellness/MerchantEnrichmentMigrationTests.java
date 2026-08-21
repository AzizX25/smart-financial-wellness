package com.azizx25.smartfinancialwellness;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantEnrichmentMigrationTests {

    private static final String SCHEMA = "smart_financial_wellness";

    @Test
    void upgradesV18ToV19WithoutRewritingEarlierMigrations() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("merchant_enrichment_upgrade_test")
                .withUsername("test_user")
                .withPassword("test_password")) {
            postgres.start();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

            Flyway throughV18 = flyway(postgres, MigrationVersion.fromVersion("18"));
            assertThat(throughV18.migrate().migrationsExecuted).isEqualTo(18);
            Integer v18Checksum = jdbc.queryForObject(
                    "SELECT checksum FROM smart_financial_wellness.flyway_schema_history WHERE version = '18'",
                    Integer.class);

            Flyway latest = flyway(postgres, null);
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            latest.validate();

            assertThat(jdbc.queryForObject(
                    "SELECT version FROM smart_financial_wellness.flyway_schema_history "
                            + "WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
                    String.class)).isEqualTo("19");
            assertThat(jdbc.queryForObject(
                    "SELECT checksum FROM smart_financial_wellness.flyway_schema_history WHERE version = '18'",
                    Integer.class)).isEqualTo(v18Checksum);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'smart_financial_wellness' "
                            + "AND table_name = 'category_prediction_evidence'",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'smart_financial_wellness' "
                            + "AND table_name = 'category_prediction' AND column_name = 'provider_id'",
                    Integer.class)).isEqualTo(1);
        }
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
