package com.azizx25.smartfinancialwellness.backend;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class BackendApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void contextLoads() {
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void infoEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedRouteReturnsJsonUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/protected-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message")
                        .value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/api/v1/protected-probe"))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void applicationUsesPostgreSqlTestcontainer() throws Exception {
        assertThat(postgresContainer.isRunning()).isTrue();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer.getJdbcUrl());
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql:");
        }
    }

    @Test
    void flywayAppliedMigrationVersionOne() {
        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_financial_wellness.flyway_schema_history "
                        + "WHERE version = '1' AND success",
                Integer.class);
        Boolean applicationHistoryExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('smart_financial_wellness.flyway_schema_history') IS NOT NULL",
                Boolean.class);
        Boolean publicHistoryExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL",
                Boolean.class);
        Boolean applicationSchemaExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'smart_financial_wellness')",
                Boolean.class);

        assertThat(appliedMigrations).isEqualTo(1);
        assertThat(applicationHistoryExists).isTrue();
        assertThat(publicHistoryExists).isFalse();
        assertThat(applicationSchemaExists).isTrue();
    }
}
