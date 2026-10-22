package com.azizx25.mockbank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azizx25.mockbank.demo.SupervisorDemoFixtureService;
import com.azizx25.mockbank.demo.SupervisorDemoScenario;
import com.azizx25.mockbank.demo.api.SupervisorDemoDtos.SeedResponse;
import com.azizx25.mockbank.exception.AdminConflictException;
import com.azizx25.mockbank.exception.InvalidAdminRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.demo-fixtures.enabled=true")
@AutoConfigureMockMvc
@Import(MockBankPostgresTestConfiguration.class)
@ActiveProfiles("demo")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SupervisorDemoFixtureIntegrationTests {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 21);
    private static final String SYNTHETIC_PASSWORD = "Fixture-" + UUID.randomUUID() + "!";
    private static final String DIFFERENT_PASSWORD = "Other-" + UUID.randomUUID() + "!";

    @Autowired SupervisorDemoFixtureService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    @Test
    @Order(2)
    void seedIsIdempotentIsolatedAndResetIsStrictlyDemoScoped() {
        int ordinaryCustomers = count("bank_customer");
        int ordinaryAccounts = count("bank_account");
        int ordinaryTransactions = count("bank_transaction");

        SeedResponse first = service.seed(SupervisorDemoScenario.VERSION, AS_OF, SYNTHETIC_PASSWORD);
        SeedResponse repeated = service.seed(SupervisorDemoScenario.VERSION, AS_OF, SYNTHETIC_PASSWORD);

        assertThat(first.createdTransactionCount()).isEqualTo(first.transactionCount());
        assertThat(repeated.createdTransactionCount()).isZero();
        assertThat(repeated.transactionCount()).isEqualTo(first.transactionCount());
        assertThat(repeated.completedMonthCount()).isEqualTo(8);
        assertThat(repeated.pendingTransactionCount()).isOne();
        assertThat(count("bank_customer")).isEqualTo(ordinaryCustomers + 1);
        assertThat(count("bank_account")).isEqualTo(ordinaryAccounts + 2);
        assertThat(count("bank_transaction")).isEqualTo(ordinaryTransactions + first.transactionCount());
        assertThat(repeated.currentAccountBalance()).isBetween(
                new BigDecimal("4000.0000"), new BigDecimal("5000.0000"));
        assertThat(repeated.savingsAccountBalance()).isBetween(
                new BigDecimal("7000.0000"), new BigDecimal("9000.0000"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM mock_bank.bank_transaction "
                        + "WHERE external_transaction_id LIKE 'SFWA-DEMO-V1-%' "
                        + "GROUP BY bank_account_id, external_transaction_id HAVING COUNT(*) > 1) duplicates",
                Integer.class)).isZero();

        assertThatThrownBy(() -> service.seed(
                SupervisorDemoScenario.VERSION, AS_OF, DIFFERENT_PASSWORD))
                .isInstanceOf(AdminConflictException.class);
        assertThatThrownBy(() -> service.reset(
                SupervisorDemoScenario.VERSION, "ordinary.customer", SupervisorDemoFixtureService.RESET_CONFIRMATION))
                .isInstanceOf(InvalidAdminRequestException.class);

        var reset = service.reset(
                SupervisorDemoScenario.VERSION,
                SupervisorDemoScenario.USERNAME,
                SupervisorDemoFixtureService.RESET_CONFIRMATION);
        assertThat(reset.removed()).isTrue();
        assertThat(count("bank_customer")).isEqualTo(ordinaryCustomers);
        assertThat(count("bank_account")).isEqualTo(ordinaryAccounts);
        assertThat(count("bank_transaction")).isEqualTo(ordinaryTransactions);
    }

    @Test
    @Order(1)
    void missingPasswordFailsBeforeMutation() {
        int customers = count("bank_customer");
        assertThatThrownBy(() -> service.seed(SupervisorDemoScenario.VERSION, AS_OF, ""))
                .isInstanceOf(InvalidAdminRequestException.class);
        assertThat(count("bank_customer")).isEqualTo(customers);
    }

    @Test
    @Order(3)
    void enabledEndpointStillRequiresAdministratorAuthentication() throws Exception {
        mockMvc.perform(post("/api/admin/demo-fixtures/supervisor")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    @WithMockUser(roles = "ADMIN")
    void enabledAdminEndpointReturnsOnlySafeFixtureMetadata() throws Exception {
        mockMvc.perform(post("/api/admin/demo-fixtures/supervisor")
                        .contentType("application/json")
                        .content("""
                                {
                                  "scenarioVersion":"supervisor-demo-v1",
                                  "asOfDate":"2026-08-21",
                                  "customerPassword":"%s"
                                }
                                """.formatted(SYNTHETIC_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(SupervisorDemoScenario.USERNAME))
                .andExpect(jsonPath("$.accountCount").value(2))
                .andExpect(jsonPath("$.customerPassword").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    @Order(5)
    void legacyAugustFixtureExtendsToOctoberWithoutChangingExistingRowsOrOrdinaryData() {
        List<Map<String, Object>> original = fixtureRowsThrough(AS_OF);
        List<Map<String, Object>> ordinary = ordinaryRows();
        int originalCount = count("bank_transaction");

        SeedResponse extended = service.seed(SupervisorDemoScenario.VERSION,
                LocalDate.of(2026, 10, 12), SYNTHETIC_PASSWORD);
        SeedResponse repeated = service.seed(SupervisorDemoScenario.VERSION,
                LocalDate.of(2026, 10, 12), SYNTHETIC_PASSWORD);

        assertThat(original).hasSize(165);
        assertThat(extended.transactionCount()).isEqualTo(202);
        assertThat(extended.createdTransactionCount()).isEqualTo(37);
        assertThat(extended.completedMonthCount()).isEqualTo(10);
        assertThat(repeated.createdTransactionCount()).isZero();
        assertThat(repeated.currentAccountBalance()).isEqualByComparingTo(extended.currentAccountBalance());
        assertThat(repeated.currentAccountAvailableBalance()).isEqualByComparingTo(extended.currentAccountAvailableBalance());
        assertThat(repeated.savingsAccountBalance()).isEqualByComparingTo(extended.savingsAccountBalance());
        assertThat(fixtureRowsThrough(AS_OF)).isEqualTo(original);
        assertThat(ordinaryRows()).isEqualTo(ordinary);
        assertThat(count("bank_transaction")).isEqualTo(originalCount + 37);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_bank.bank_transaction "
                + "WHERE external_transaction_id LIKE 'SFWA-DEMO-V1-%OPENING-%'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM mock_bank.bank_transaction "
                + "WHERE external_transaction_id LIKE 'SFWA-DEMO-V1-%' "
                + "GROUP BY bank_account_id, external_transaction_id HAVING COUNT(*) > 1) duplicates",
                Integer.class)).isZero();
    }

    @Test
    @Order(6)
    void changedLegacyTransactionStillFailsAndRollsBackWithoutAppending() {
        String reference = "SFWA-DEMO-V1-2026-02-SALARY";
        jdbc.update("UPDATE mock_bank.bank_transaction SET description = description || ' changed' "
                + "WHERE external_transaction_id = ?", reference);
        List<Map<String, Object>> before = fixtureRowsThrough(LocalDate.of(2026, 10, 31));
        try {
            assertThatThrownBy(() -> service.seed(SupervisorDemoScenario.VERSION,
                    LocalDate.of(2026, 10, 14), SYNTHETIC_PASSWORD))
                    .isInstanceOf(AdminConflictException.class)
                    .hasMessage("Supervisor demo transaction conflicts with existing data");
            assertThat(fixtureRowsThrough(LocalDate.of(2026, 10, 31))).isEqualTo(before);
        } finally {
            jdbc.update("UPDATE mock_bank.bank_transaction SET description = ? WHERE external_transaction_id = ?",
                    "VIREMENT SALAIRE CARTHAGE DIGITAL SERVICES", reference);
        }
    }

    @Test
    @Order(7)
    void missingHistoryAnchorAndRegressingDatesAreRejectedWithoutResetting() {
        String reference = "SFWA-DEMO-V1-2025-12-OPENING-CURRENT";
        jdbc.update("UPDATE mock_bank.bank_transaction SET external_transaction_id = ? "
                + "WHERE external_transaction_id = ?", "SFWA-DEMO-V1-UNKNOWN-OPENING", reference);
        int before = count("bank_transaction");
        try {
            assertThatThrownBy(() -> service.seed(SupervisorDemoScenario.VERSION,
                    LocalDate.of(2026, 10, 14), SYNTHETIC_PASSWORD))
                    .isInstanceOf(AdminConflictException.class)
                    .hasMessage("Supervisor demo history anchor could not be verified");
            assertThat(count("bank_transaction")).isEqualTo(before);
        } finally {
            jdbc.update("UPDATE mock_bank.bank_transaction SET external_transaction_id = ? "
                    + "WHERE external_transaction_id = ?", reference, "SFWA-DEMO-V1-UNKNOWN-OPENING");
        }
        assertThatThrownBy(() -> service.seed(SupervisorDemoScenario.VERSION,
                LocalDate.of(2026, 10, 11), SYNTHETIC_PASSWORD))
                .isInstanceOf(AdminConflictException.class)
                .hasMessage("Supervisor demo as-of date precedes existing transaction history");
        assertThat(count("bank_transaction")).isEqualTo(before);
    }

    private List<Map<String, Object>> fixtureRowsThrough(LocalDate date) {
        return jdbc.queryForList("SELECT * FROM mock_bank.bank_transaction "
                + "WHERE external_transaction_id LIKE 'SFWA-DEMO-V1-%' AND booking_date <= ? "
                + "ORDER BY external_transaction_id", date);
    }

    private List<Map<String, Object>> ordinaryRows() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>(jdbc.queryForList(
                "SELECT id, external_customer_id, display_name, country_code, status, created_at "
                        + "FROM mock_bank.bank_customer WHERE external_customer_id <> ? ORDER BY id",
                SupervisorDemoScenario.CUSTOMER_ID));
        rows.addAll(jdbc.queryForList("SELECT a.* FROM mock_bank.bank_account a "
                + "JOIN mock_bank.bank_customer c ON c.id = a.bank_customer_id "
                + "WHERE c.external_customer_id <> ? ORDER BY a.id", SupervisorDemoScenario.CUSTOMER_ID));
        rows.addAll(jdbc.queryForList("SELECT t.* FROM mock_bank.bank_transaction t "
                + "JOIN mock_bank.bank_account a ON a.id = t.bank_account_id "
                + "JOIN mock_bank.bank_customer c ON c.id = a.bank_customer_id "
                + "WHERE c.external_customer_id <> ? ORDER BY t.id", SupervisorDemoScenario.CUSTOMER_ID));
        return rows;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mock_bank." + table, Integer.class);
    }
}
