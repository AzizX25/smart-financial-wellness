package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class SalaryPlanningApiIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void rejectsUnauthenticatedAccessAndCreatesServerCalculatedOwnedPlan() throws Exception {
        UUID food = category("FOOD");
        AppRegistration user = registerAndActivate();
        insertHistoricalExpense(user.userId(), food, YearMonth.of(2026, 7), "100.0000", "TND");

        mockMvc.perform(get("/api/salary-plans/2026-08").param("currency", "TND"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/salary-plans/2026-08")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "TND")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "incomes": [{"label": "  Salaire  ", "expectedAmount": 2500.0000}],
                                  "allocationOverrides": [
                                    {"categoryId": "%s", "confirmedAmount": 150.0000}
                                  ]
                                }
                                """.formatted(food)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-08"))
                .andExpect(jsonPath("$.currency").value("TND"))
                .andExpect(jsonPath("$.incomes[0].label").value("Salaire"))
                .andExpect(jsonPath("$.totalIncome").value(2500.0))
                .andExpect(jsonPath("$.totalExpenses").value(150.0))
                .andExpect(jsonPath("$.savingCapacity").value(2350.0))
                .andExpect(jsonPath("$.deficit").value(0.0))
                .andExpect(jsonPath("$.recommendationMetadata.metadataAvailable").value(true))
                .andExpect(jsonPath("$.recommendationMetadata.periodStart").value("2026-05-01"))
                .andExpect(jsonPath("$.recommendationMetadata.periodEndInclusive").value("2026-07-31"))
                .andExpect(jsonPath("$.recommendationMetadata.usableMonthCount").value(1))
                .andExpect(jsonPath("$.recommendationMetadata.method").value("HISTORICAL_MONTHLY_AVERAGE"))
                .andExpect(jsonPath("$.recommendationMetadata.methodVersion").value("1"))
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].recommendedAmount"
                        .formatted(food)).value(100.0))
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].confirmedAmount"
                        .formatted(food)).value(150.0))
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].effectiveAmount"
                        .formatted(food)).value(150.0))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.incomes[0].id").doesNotExist())
                .andExpect(jsonPath("$.allocations[0].id").doesNotExist());

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT expected_income_total, recommended_expense_budget, calculated_saving_capacity, "
                        + "calculation_method, method_version, recommendation_metadata_available, "
                        + "recommendation_usable_month_count, recommendation_fallback_reason "
                        + "FROM " + SCHEMA + "monthly_plan WHERE user_id = ? AND period_month = DATE '2026-08-01' "
                        + "AND currency = 'TND'",
                user.userId());
        assertThat((BigDecimal) stored.get("expected_income_total")).isEqualByComparingTo("2500.0000");
        assertThat((BigDecimal) stored.get("recommended_expense_budget")).isEqualByComparingTo("100.0000");
        assertThat((BigDecimal) stored.get("calculated_saving_capacity")).isEqualByComparingTo("2350.0000");
        assertThat(stored.get("calculation_method")).isEqualTo("HISTORICAL_MONTHLY_AVERAGE");
        assertThat(stored.get("method_version")).isEqualTo("1");
        assertThat(stored.get("recommendation_metadata_available")).isEqualTo(true);
        assertThat(((Number) stored.get("recommendation_usable_month_count")).intValue()).isOne();
        assertThat(stored.get("recommendation_fallback_reason")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recommendation_period_start FROM " + SCHEMA
                        + "monthly_plan WHERE user_id = ? AND period_month = DATE '2026-08-01' AND currency = 'TND'",
                LocalDate.class,
                user.userId())).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recommendation_period_end FROM " + SCHEMA
                        + "monthly_plan WHERE user_id = ? AND period_month = DATE '2026-08-01' AND currency = 'TND'",
                LocalDate.class,
                user.userId())).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void readsPersistedRecommendationSnapshotUntilPutRefreshesAmountsAndMetadataTogether() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID food = category("FOOD");
        insertHistoricalExpense(user.userId(), food, YearMonth.of(2026, 7), "60.0000", "TND");

        putPlan(user, "2026-08", "TND", emptyRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMetadata.usableMonthCount").value(1))
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].recommendedAmount"
                        .formatted(food)).value(60.0));

        insertHistoricalExpense(user.userId(), food, YearMonth.of(2026, 6), "20.0000", "TND");

        mockMvc.perform(get("/api/salary-plans/2026-08")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMetadata.usableMonthCount").value(1))
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].recommendedAmount"
                        .formatted(food)).value(60.0));
        mockMvc.perform(get("/api/salary-plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationMetadata.usableMonthCount").value(1))
                .andExpect(jsonPath("$.items[0].allocations[?(@.categoryId == '%s')].recommendedAmount"
                        .formatted(food)).value(60.0));

        putPlan(user, "2026-08", "TND", emptyRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMetadata.metadataAvailable").value(true))
                .andExpect(jsonPath("$.recommendationMetadata.usableMonthCount").value(2))
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].recommendedAmount"
                        .formatted(food)).value(40.0));

        Map<String, Object> refreshed = jdbcTemplate.queryForMap(
                "SELECT recommended_expense_budget, recommendation_usable_month_count "
                        + "FROM " + SCHEMA
                        + "monthly_plan WHERE user_id = ? AND period_month = DATE '2026-08-01' AND currency = 'TND'",
                user.userId());
        assertThat((BigDecimal) refreshed.get("recommended_expense_budget")).isEqualByComparingTo("40.0000");
        assertThat(((Number) refreshed.get("recommendation_usable_month_count")).intValue()).isEqualTo(2);
    }

    @Test
    void repeatedPutUpdatesOneAggregateAndReplacesIncomesAndOverrides() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID food = category("FOOD");
        UUID housing = category("HOUSING");
        String initialRequest = """
                {
                  "incomes": [
                    {"label": "Salaire", "expectedAmount": 100.0000},
                    {"label": "Mission", "expectedAmount": 20.0000}
                  ],
                  "allocationOverrides": [
                    {"categoryId": "%s", "confirmedAmount": 30.0000},
                    {"categoryId": "%s", "confirmedAmount": 40.0000}
                  ]
                }
                """.formatted(food, housing);

        putPlan(user, "2026-08", "TND", initialRequest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMetadata.metadataAvailable").value(true))
                .andExpect(jsonPath("$.recommendationMetadata.usableMonthCount").value(0))
                .andExpect(jsonPath("$.recommendationMetadata.fallbackReason").value("NO_USABLE_HISTORY"));
        putPlan(user, "2026-08", "TND", initialRequest).andExpect(status().isOk());

        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA
                        + "monthly_plan WHERE user_id = ? AND period_month = DATE '2026-08-01' AND currency = 'TND'",
                UUID.class,
                user.userId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "monthly_plan WHERE user_id = ?", Integer.class, user.userId()))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "planned_income WHERE monthly_plan_id = ?", Integer.class, planId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS total, COUNT(DISTINCT category_id) AS distinct_total FROM "
                        + SCHEMA + "budget_allocation WHERE monthly_plan_id = ?",
                planId))
                .satisfies(counts -> assertThat(counts.get("total")).isEqualTo(counts.get("distinct_total")));

        putPlan(user, "2026-08", "TND", """
                {
                  "incomes": [{"label": "Remplacement", "expectedAmount": 10.0000}],
                  "allocationOverrides": [
                    {"categoryId": "%s", "confirmedAmount": 50.0000}
                  ]
                }
                """.formatted(food))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomes.length()").value(1))
                .andExpect(jsonPath("$.incomes[0].label").value("Remplacement"))
                .andExpect(jsonPath("$.totalExpenses").value(50.0))
                .andExpect(jsonPath("$.savingCapacity").value(-40.0))
                .andExpect(jsonPath("$.deficit").value(40.0))
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].confirmedAmount"
                        .formatted(food)).value(50.0));

        assertThat(jdbcTemplate.queryForList(
                "SELECT label FROM " + SCHEMA + "planned_income WHERE monthly_plan_id = ?", String.class, planId))
                .containsExactly("Remplacement");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confirmed_amount FROM " + SCHEMA
                        + "budget_allocation WHERE monthly_plan_id = ? AND category_id = ?",
                BigDecimal.class,
                planId,
                housing)).isNull();
    }

    @Test
    void getIsOwnerScopedAndRejectsClientSuppliedDerivedValues() throws Exception {
        AppRegistration owner = registerAndActivate();
        AppRegistration other = registerAndActivate();

        mockMvc.perform(put("/api/salary-plans/2026-08")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "TND")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"incomes": [], "allocationOverrides": [], "totalIncome": 9999.0000}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/salary-plans/2026-08")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "TND")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "incomes": [],
                                  "allocationOverrides": [{
                                    "categoryId": "%s",
                                    "confirmedAmount": 1.0000,
                                    "recommendedAmount": 9000.0000
                                  }]
                                }
                                """.formatted(category("FOOD"))))
                .andExpect(status().isBadRequest());

        putPlan(owner, "2026-08", "TND", emptyRequest()).andExpect(status().isOk());
        mockMvc.perform(get("/api/salary-plans/2026-08")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").doesNotExist());
        mockMvc.perform(get("/api/salary-plans/2026-08")
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token()))
                        .param("currency", "TND"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SALARY_PLAN_NOT_FOUND"));
    }

    @Test
    void listFiltersCurrencyPaginatesAndOrdersNewestFirst() throws Exception {
        AppRegistration user = registerAndActivate();
        putPlan(user, "2026-06", "TND", emptyRequest()).andExpect(status().isOk());
        putPlan(user, "2026-08", "TND", emptyRequest()).andExpect(status().isOk());
        putPlan(user, "2026-07", "TND", emptyRequest()).andExpect(status().isOk());
        putPlan(user, "2026-09", "USD", emptyRequest()).andExpect(status().isOk());

        mockMvc.perform(get("/api/salary-plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "TND")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].month").value("2026-08"))
                .andExpect(jsonPath("$.items[1].month").value("2026-07"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));
        mockMvc.perform(get("/api/salary-plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].month").value("2026-09"));
    }

    @Test
    void proposalsUseHistoryWithoutPersistingAPlan() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID food = category("FOOD");
        insertHistoricalExpense(user.userId(), food, YearMonth.of(2026, 7), "75.0000", "TND");

        assertThat(planCount(user.userId())).isZero();
        mockMvc.perform(get("/api/salary-plans/proposals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("month", "2026-08")
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-08"))
                .andExpect(jsonPath("$.totalRecommendedExpenses").value(75.0))
                .andExpect(jsonPath("$.recommendationMetadata.metadataAvailable").value(true))
                .andExpect(jsonPath("$.recommendationMetadata.usableMonthCount").value(1))
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].recommendedAmount"
                        .formatted(food)).value(75.0));
        assertThat(planCount(user.userId())).isZero();
    }

    @Test
    void legacyPlanReportsUnavailableMetadataWithoutRegeneratingIt() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID food = category("FOOD");
        insertHistoricalExpense(user.userId(), food, YearMonth.of(2026, 7), "99.0000", "TND");
        insertLegacyPlan(user.userId(), food);

        mockMvc.perform(get("/api/salary-plans/2026-08")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationMetadata.metadataAvailable").value(false))
                .andExpect(jsonPath("$.recommendationMetadata.periodStart").doesNotExist())
                .andExpect(jsonPath("$.recommendationMetadata.periodEndInclusive").doesNotExist())
                .andExpect(jsonPath("$.recommendationMetadata.usableMonthCount").doesNotExist())
                .andExpect(jsonPath("$.recommendationMetadata.method").doesNotExist())
                .andExpect(jsonPath("$.recommendationMetadata.methodVersion").doesNotExist())
                .andExpect(jsonPath("$.recommendationMetadata.fallbackReason").doesNotExist())
                .andExpect(jsonPath("$.allocations[?(@.categoryId == '%s')].recommendedAmount"
                        .formatted(food)).value(44.0));
    }

    @Test
    void rejectsInvalidMonthCurrencyAmountsDuplicatesAndIneligibleCategories() throws Exception {
        AppRegistration user = registerAndActivate();
        AppRegistration other = registerAndActivate();
        UUID food = category("FOOD");
        UUID income = category("INCOME_SALARY");
        UUID foreignExpense = createCategory(other, "Foreign expense");

        mockMvc.perform(get("/api/salary-plans/2026-13")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "TND"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/salary-plans/2026-08")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .param("currency", "tnd"))
                .andExpect(status().isBadRequest());
        putPlan(user, "2026-08", "TND", """
                {"incomes": [{"label": "Scale", "expectedAmount": 1.00001}], "allocationOverrides": []}
                """).andExpect(status().isBadRequest());
        putPlan(user, "2026-08", "TND", """
                {"incomes": [{"label": "Negative", "expectedAmount": -1.0000}], "allocationOverrides": []}
                """).andExpect(status().isBadRequest());
        putPlan(user, "2026-08", "TND", """
                {
                  "incomes": [],
                  "allocationOverrides": [
                    {"categoryId": "%s", "confirmedAmount": 1.0000},
                    {"categoryId": "%s", "confirmedAmount": 2.0000}
                  ]
                }
                """.formatted(food, food))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SALARY_PLAN"));
        putPlan(user, "2026-08", "TND", overrideRequest(income, "1.0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SALARY_PLAN"));
        putPlan(user, "2026-08", "TND", overrideRequest(foreignExpense, "1.0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SALARY_PLAN"));
    }

    private org.springframework.test.web.servlet.ResultActions putPlan(
            AppRegistration user,
            String month,
            String currency,
            String body) throws Exception {
        return mockMvc.perform(put("/api/salary-plans/{month}", month)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .param("currency", currency)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private AppRegistration registerAndActivate() throws Exception {
        String email = "salary-api-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPass123!",
                                  "firstName": "Salary",
                                  "lastName": "Tester",
                                  "displayName": "Salary Tester"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        jdbcTemplate.update("UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbcTemplate.update(
                "UPDATE " + SCHEMA
                        + "authentication_identity SET status = 'ACTIVE', verified_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                userId);
        return new AppRegistration(userId, body.get("accessToken").asText());
    }

    private UUID createCategory(AppRegistration user, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"nature\":\"EXPENSE\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void insertHistoricalExpense(
            UUID userId,
            UUID categoryId,
            YearMonth month,
            String amount,
            String currency) {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, account_origin, name, account_type, currency) "
                        + "VALUES (?, ?, 'MANUAL', 'Salary API history', 'CHECKING', ?)",
                accountId,
                userId,
                currency);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, booking_date, amount, "
                        + "direction, currency, raw_description, normalized_description, category_source, categorized_at) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, 'DEBIT', ?, 'Salary API history', "
                        + "'salary api history', 'USER', CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                userId,
                accountId,
                categoryId,
                month.atDay(5),
                new BigDecimal(amount),
                currency);
    }

    private void insertLegacyPlan(UUID userId, UUID categoryId) {
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "monthly_plan "
                        + "(id, user_id, period_month, currency, expected_income_total, recommended_expense_budget, "
                        + "calculated_saving_capacity, calculation_method, method_version) "
                        + "VALUES (?, ?, DATE '2026-08-01', 'TND', 0.0000, 44.0000, -44.0000, "
                        + "'LEGACY_DETERMINISTIC', 'legacy-1')",
                planId,
                userId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "budget_allocation "
                        + "(id, monthly_plan_id, category_id, recommended_amount, recommendation_origin) "
                        + "VALUES (?, ?, ?, 44.0000, 'RULES')",
                UUID.randomUUID(),
                planId,
                categoryId);
    }

    private UUID category(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "category WHERE code = ? AND user_id IS NULL",
                UUID.class,
                code);
    }

    private int planCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "monthly_plan WHERE user_id = ?",
                Integer.class,
                userId);
    }

    private String emptyRequest() {
        return "{\"incomes\":[],\"allocationOverrides\":[]}";
    }

    private String overrideRequest(UUID categoryId, String amount) {
        return """
                {
                  "incomes": [],
                  "allocationOverrides": [{"categoryId": "%s", "confirmedAmount": %s}]
                }
                """.formatted(categoryId, amount);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record AppRegistration(UUID userId, String token) {
    }
}
