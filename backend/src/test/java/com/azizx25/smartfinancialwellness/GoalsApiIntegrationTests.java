package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, GoalsApiIntegrationTests.FixedClockConfiguration.class})
class GoalsApiIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final Instant TEST_INSTANT = Instant.now();
    private static final LocalDate TODAY = LocalDate.ofInstant(TEST_INSTANT, ZoneOffset.UTC);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void requiresAuthenticationAndCreatesAnOwnedGoalWithoutExposingOwnership() throws Exception {
        mockMvc.perform(get("/api/goals").param("currency", "TND"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalRequest("TND", "1000.0000", "100.0000")))
                .andExpect(status().isUnauthorized());

        AppRegistration user = registerAndActivate();
        MvcResult created = createGoal(user, "TND", "1000.0000", "100.0000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Fonds d'urgence"))
                .andExpect(jsonPath("$.description").value("Sécurité financière"))
                .andExpect(jsonPath("$.type").value("EMERGENCY_FUND"))
                .andExpect(jsonPath("$.currency").value("TND"))
                .andExpect(jsonPath("$.contributionTotal").value(0.0))
                .andExpect(jsonPath("$.currentSaved").value(100.0))
                .andExpect(jsonPath("$.remainingAmount").value(900.0))
                .andExpect(jsonPath("$.progressPercentage").value(10.0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andReturn();
        UUID goalId = id(created);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM " + SCHEMA + "financial_goal WHERE id = ?",
                UUID.class,
                goalId)).isEqualTo(user.userId());
        assertAudit(user.userId(), "GOAL_CREATED", goalId);

        mockMvc.perform(post("/api/goals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Injected",
                                  "type": "OTHER",
                                  "currency": "TND",
                                  "targetAmount": 10.0000,
                                  "initialAmount": 0.0000,
                                  "priority": "LOW",
                                  "userId": "%s",
                                  "status": "ARCHIVED"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validatesStrictGoalInputsAndCreatesAchievedOnlyAtTheSchemaSupportedBoundary() throws Exception {
        AppRegistration user = registerAndActivate();

        MvcResult achieved = createGoal(user, "TND", "100.0000", "100.0000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACHIEVED"))
                .andExpect(jsonPath("$.remainingAmount").value(0.0))
                .andExpect(jsonPath("$.progressPercentage").value(100.0))
                .andReturn();
        mockMvc.perform(put("/api/goals/{goalId}", id(achieved))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalRequest("TND", "200.0000", "100.0000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.remainingAmount").value(100.0));
        createGoal(user, "TND", "100.0000", "101.0000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GOAL"));
        createGoal(user, "tnd", "100.0000", "0.0000")
                .andExpect(status().isBadRequest());
        createGoal(user, "TND", "0.0000", "0.0000")
                .andExpect(status().isBadRequest());
        createGoal(user, "TND", "100.00001", "0.0000")
                .andExpect(status().isBadRequest());
        createGoal(user, "TND", "100.0000", "-0.0001")
                .andExpect(status().isBadRequest());
    }

    @Test
    void isolatesUsersAndCurrenciesAndArchivesLogically() throws Exception {
        AppRegistration owner = registerAndActivate();
        AppRegistration other = registerAndActivate();
        UUID tndGoal = id(createGoal(owner, "TND", "500.0000", "0.0000").andReturn());
        UUID usdGoal = id(createGoal(owner, "USD", "600.0000", "0.0000").andReturn());
        createGoal(other, "TND", "700.0000", "0.0000").andExpect(status().isCreated());

        mockMvc.perform(get("/api/goals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(tndGoal.toString()));
        mockMvc.perform(get("/api/goals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(usdGoal.toString()));
        mockMvc.perform(get("/api/goals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "tnd"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/goals/{goalId}", tndGoal)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/goals/{goalId}", tndGoal)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalRequest("TND", "800.0000", "0.0000")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/goals/{goalId}", tndGoal)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound());

        addContribution(owner, tndGoal, "25.0000", TODAY.minusDays(1).toString())
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/goals/{goalId}", tndGoal)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM " + SCHEMA + "financial_goal WHERE id = ?",
                String.class,
                tndGoal)).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "goal_contribution WHERE financial_goal_id = ?",
                Integer.class,
                tndGoal)).isOne();

        mockMvc.perform(get("/api/goals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "TND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/goals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .param("currency", "TND")
                        .param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ARCHIVED"));
        addContribution(owner, tndGoal, "1.0000", null).andExpect(status().isConflict());
        assertAudit(owner.userId(), "GOAL_ARCHIVED", tndGoal);
    }

    @Test
    void putFullyReplacesEditableFieldsAndRecalculatesProgress() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID goalId = id(createGoal(user, "TND", "1000.0000", "100.0000").andReturn());
        addContribution(user, goalId, "100.0000", TODAY.minusDays(1).toString())
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/goals/{goalId}", goalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  Voyage familial  ",
                                  "description": "  Été 2027  ",
                                  "type": "TRAVEL",
                                  "currency": "TND",
                                  "targetAmount": 500.0000,
                                  "initialAmount": 50.0000,
                                  "targetDate": "2027-07-31",
                                  "priority": "MEDIUM"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Voyage familial"))
                .andExpect(jsonPath("$.description").value("Été 2027"))
                .andExpect(jsonPath("$.type").value("TRAVEL"))
                .andExpect(jsonPath("$.targetAmount").value(500.0))
                .andExpect(jsonPath("$.initialAmount").value(50.0))
                .andExpect(jsonPath("$.contributionTotal").value(100.0))
                .andExpect(jsonPath("$.currentSaved").value(150.0))
                .andExpect(jsonPath("$.remainingAmount").value(350.0))
                .andExpect(jsonPath("$.progressPercentage").value(30.0));
        assertAudit(user.userId(), "GOAL_UPDATED", goalId);

        mockMvc.perform(put("/api/goals/{goalId}", goalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Incomplete",
                                  "type": "OTHER",
                                  "currency": "TND",
                                  "targetAmount": 100.0000,
                                  "initialAmount": 0.0000
                                }
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/goals/{goalId}", goalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalRequest("USD", "500.0000", "50.0000")))
                .andExpect(status().isConflict());

        UUID unattachedGoal = id(createGoal(user, "TND", "100.0000", "0.0000").andReturn());
        mockMvc.perform(put("/api/goals/{goalId}", unattachedGoal)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalRequest("USD", "100.0000", "0.0000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void enforcesStatusTransitionsAndCancelsOnlyCurrentAndFutureAllocations() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID goalId = id(createGoal(user, "TND", "1000.0000", "0.0000").andReturn());
        UUID past = insertPlan(user.userId(), YearMonth.from(TODAY).minusMonths(1).atDay(1));
        UUID current = insertPlan(user.userId(), YearMonth.from(TODAY).atDay(1));
        UUID future = insertPlan(user.userId(), YearMonth.from(TODAY).plusMonths(1).atDay(1));
        insertConfirmedAllocation(past, goalId, "10.0000");
        insertConfirmedAllocation(current, goalId, "20.0000");
        insertConfirmedAllocation(future, goalId, "30.0000");

        changeStatus(user, goalId, "PAUSED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
        assertThat(allocationStatus(past, goalId)).isEqualTo("CONFIRMED");
        assertThat(allocationStatus(current, goalId)).isEqualTo("CANCELLED");
        assertThat(allocationStatus(future, goalId)).isEqualTo("CANCELLED");

        changeStatus(user, goalId, "PAUSED").andExpect(status().isOk());
        changeStatus(user, goalId, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        changeStatus(user, goalId, "ACHIEVED")
                .andExpect(status().isConflict());
        changeStatus(user, goalId, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        changeStatus(user, goalId, "ACTIVE")
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/goals/{goalId}", goalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalRequest("TND", "1000.0000", "0.0000")))
                .andExpect(status().isConflict());
        assertAudit(user.userId(), "GOAL_STATUS_CHANGED", goalId);
    }

    @Test
    void manualContributionsDriveProgressAchievementCorrectionOrderingAndValidation() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID goalId = id(createGoal(user, "TND", "100.0000", "0.0000").andReturn());
        UUID currentPlan = insertPlan(user.userId(), YearMonth.from(TODAY).atDay(1));
        insertConfirmedAllocation(currentPlan, goalId, "20.0000");

        addContribution(user, goalId, "60.0000", TODAY.minusDays(1).toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentSaved").value(60.0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        addContribution(user, goalId, "40.0000", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentSaved").value(100.0))
                .andExpect(jsonPath("$.remainingAmount").value(0.0))
                .andExpect(jsonPath("$.status").value("ACHIEVED"));
        assertThat(allocationStatus(currentPlan, goalId)).isEqualTo("CANCELLED");
        addContribution(user, goalId, "1.0000", null).andExpect(status().isConflict());

        MvcResult listed = mockMvc.perform(get("/api/goals/{goalId}/contributions", goalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(40.0))
                .andExpect(jsonPath("$[0].contributionDate").value(TODAY.toString()))
                .andExpect(jsonPath("$[0].source").value("MANUAL"))
                .andExpect(jsonPath("$[0].goalId").doesNotExist())
                .andExpect(jsonPath("$[0].transactionId").doesNotExist())
                .andExpect(jsonPath("$[1].amount").value(60.0))
                .andReturn();
        UUID correctionId = UUID.fromString(objectMapper.readTree(
                listed.getResponse().getContentAsString()).get(0).get("id").asText());

        mockMvc.perform(delete("/api/goals/{goalId}/contributions/{contributionId}", goalId, correctionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSaved").value(60.0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        addContribution(user, goalId, "1.0000", TODAY.plusDays(1).toString())
                .andExpect(status().isBadRequest());
        addContribution(user, goalId, "0.0000", null).andExpect(status().isBadRequest());
        addContribution(user, goalId, "-1.0000", null).andExpect(status().isBadRequest());
        addContribution(user, goalId, "1.00001", null).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/goals/{goalId}/contributions", goalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 1.0000, "source": "TRANSACTION"}
                                """))
                .andExpect(status().isBadRequest());
        assertAudit(user.userId(), "GOAL_CONTRIBUTION_CREATED", null);
        assertAudit(user.userId(), "GOAL_CONTRIBUTION_DELETED", correctionId);
        assertAudit(user.userId(), "GOAL_STATUS_CHANGED", goalId);
    }

    @Test
    void rejectsDeletionOfTransactionLinkedContributions() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID goalId = id(createGoal(user, "TND", "500.0000", "0.0000").andReturn());
        UUID transactionId = insertManualTransaction(user.userId());
        UUID contributionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_contribution "
                        + "(id, financial_goal_id, transaction_id, source, amount, contribution_date) "
                        + "VALUES (?, ?, ?, 'TRANSACTION', 25.0000, DATE '2026-08-10')",
                contributionId,
                goalId,
                transactionId);

        mockMvc.perform(delete("/api/goals/{goalId}/contributions/{contributionId}", goalId, contributionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "goal_contribution WHERE id = ?",
                Integer.class,
                contributionId)).isOne();
    }

    private org.springframework.test.web.servlet.ResultActions createGoal(
            AppRegistration user,
            String currency,
            String target,
            String initial) throws Exception {
        return mockMvc.perform(post("/api/goals")
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(goalRequest(currency, target, initial)));
    }

    private String goalRequest(String currency, String target, String initial) {
        return """
                {
                  "name": "  Fonds d'urgence  ",
                  "description": "  Sécurité financière  ",
                  "type": "EMERGENCY_FUND",
                  "currency": "%s",
                  "targetAmount": %s,
                  "initialAmount": %s,
                  "targetDate": "2027-12-31",
                  "priority": "HIGH"
                }
                """.formatted(currency, target, initial);
    }

    private org.springframework.test.web.servlet.ResultActions addContribution(
            AppRegistration user,
            UUID goalId,
            String amount,
            String date) throws Exception {
        String dateField = date == null ? "" : ", \"contributionDate\": \"" + date + "\"";
        return mockMvc.perform(post("/api/goals/{goalId}/contributions", goalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + dateField + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions changeStatus(
            AppRegistration user,
            UUID goalId,
            String statusValue) throws Exception {
        return mockMvc.perform(patch("/api/goals/{goalId}/status", goalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + statusValue + "\"}"));
    }

    private AppRegistration registerAndActivate() throws Exception {
        String email = "goals-api-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPass123!",
                                  "firstName": "Goals",
                                  "lastName": "Tester",
                                  "displayName": "Goals Tester"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        jdbcTemplate.update("UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbcTemplate.update(
                "UPDATE " + SCHEMA
                        + "authentication_identity SET status = 'ACTIVE', verified_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ?",
                userId);
        return new AppRegistration(userId, body.get("accessToken").asText());
    }

    private UUID insertPlan(UUID userId, LocalDate month) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "monthly_plan "
                        + "(id, user_id, period_month, currency, calculated_saving_capacity, "
                        + "calculation_method, method_version) "
                        + "VALUES (?, ?, ?, 'TND', 500.0000, 'DETERMINISTIC', '1')",
                id,
                userId,
                month);
        return id;
    }

    private void insertConfirmedAllocation(UUID planId, UUID goalId, String amount) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_allocation "
                        + "(id, monthly_plan_id, financial_goal_id, planned_amount, confirmed_amount, "
                        + "status, confirmed_at) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', CURRENT_TIMESTAMP)",
                UUID.randomUUID(),
                planId,
                goalId,
                new BigDecimal(amount),
                new BigDecimal(amount));
    }

    private String allocationStatus(UUID planId, UUID goalId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM " + SCHEMA
                        + "goal_allocation WHERE monthly_plan_id = ? AND financial_goal_id = ?",
                String.class,
                planId,
                goalId);
    }

    private UUID insertManualTransaction(UUID userId) {
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID uncategorized = jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "category WHERE user_id IS NULL AND code = 'UNCATEGORIZED'",
                UUID.class);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, account_origin, name, account_type, currency) "
                        + "VALUES (?, ?, 'MANUAL', 'Goals test account', 'SAVINGS', 'TND')",
                accountId,
                userId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, booking_date, "
                        + "amount, direction, currency, raw_description, normalized_description, category_source) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', DATE '2026-08-10', 25.0000, 'DEBIT', 'TND', "
                        + "'Goal contribution', 'goal contribution', 'UNCATEGORIZED')",
                transactionId,
                userId,
                accountId,
                uncategorized);
        return transactionId;
    }

    private void assertAudit(UUID userId, String action, UUID resourceId) {
        String resourceClause = resourceId == null ? "" : " AND resource_id = ?";
        Object[] arguments = resourceId == null
                ? new Object[] {userId, action}
                : new Object[] {userId, action, resourceId};
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "audit_log_entry "
                        + "WHERE actor_id = ? AND action = ? AND change_summary = '{}'::jsonb"
                        + resourceClause,
                Integer.class,
                arguments);
        assertThat(count).isPositive();
    }

    private UUID id(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record AppRegistration(UUID userId, String token) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock goalsTestClock() {
            return Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
        }
    }
}
