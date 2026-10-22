package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
        PostgresTestConfiguration.class,
        GoalAllocationSimulationApiIntegrationTests.FixedClockConfiguration.class
})
class GoalAllocationSimulationApiIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";
    private static final Instant TEST_INSTANT = Instant.now();
    private static final LocalDate TODAY = LocalDate.ofInstant(TEST_INSTANT, ZoneOffset.UTC);
    private static final YearMonth CURRENT_MONTH = YearMonth.from(TODAY);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void rejectsUnauthenticatedAccessAndReturnsAnExactMissingPlanOverviewWithoutFallback() throws Exception {
        mockMvc.perform(get("/api/goals/allocations/{month}", CURRENT_MONTH)
                        .param("currency", "TND"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/goals/allocations/{month}", CURRENT_MONTH)
                        .param("currency", "TND")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocations\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/goals/{goalId}/simulations", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyContribution\":100.0000}"))
                .andExpect(status().isUnauthorized());

        AppRegistration user = registerAndActivate();
        int allocationCountBefore = count("goal_allocation", "TRUE");
        insertPlan(user.userId(), CURRENT_MONTH.minusMonths(1), "TND", "400.0000");
        insertPlan(user.userId(), CURRENT_MONTH, "USD", "500.0000");
        int planCountBefore = count("monthly_plan", "user_id = ?", user.userId());

        MvcResult result = allocationGet(user, CURRENT_MONTH, "TND")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(CURRENT_MONTH.toString()))
                .andExpect(jsonPath("$.currency").value("TND"))
                .andExpect(jsonPath("$.capacityAvailable").value(false))
                .andExpect(jsonPath("$.salaryPlanSavingCapacity").doesNotExist())
                .andExpect(jsonPath("$.allocatableCapacity").value(0.0))
                .andExpect(jsonPath("$.totalConfirmedAllocation").value(0.0))
                .andExpect(jsonPath("$.remainingUnallocatedCapacity").value(0.0))
                .andExpect(jsonPath("$.allocations").isEmpty())
                .andReturn();

        JsonNode body = json(result);
        assertThat(body.has("userId")).isFalse();
        assertThat(body.has("planId")).isFalse();
        assertThat(count("monthly_plan", "user_id = ?", user.userId())).isEqualTo(planCountBefore);
        assertThat(count("goal_allocation", "TRUE")).isEqualTo(allocationCountBefore);
    }

    @Test
    void returnsExactCapacityAllocationsAndDeterministicConfirmedProjection() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID planId = insertPlan(user.userId(), CURRENT_MONTH, "TND", "600.0000");
        UUID confirmedGoal = createGoal(user, "TND", "1000.0000", "100.0000", 12);
        UUID cancelledGoal = createGoal(user, "TND", "700.0000", "0.0000", 12);
        insertContribution(confirmedGoal, "100.0000");
        insertAllocation(planId, confirmedGoal, "300.0000", "CONFIRMED");
        insertAllocation(planId, cancelledGoal, "50.0000", "CANCELLED");

        MvcResult result = allocationGet(user, CURRENT_MONTH, "TND")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacityAvailable").value(true))
                .andExpect(jsonPath("$.salaryPlanSavingCapacity").value(600.0))
                .andExpect(jsonPath("$.allocatableCapacity").value(600.0))
                .andExpect(jsonPath("$.totalConfirmedAllocation").value(300.0))
                .andExpect(jsonPath("$.remainingUnallocatedCapacity").value(300.0))
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andReturn();

        JsonNode body = json(result);
        JsonNode confirmed = findAllocation(body, confirmedGoal);
        assertThat(confirmed.get("goalName").asText()).isEqualTo("Objectif test");
        assertThat(confirmed.get("goalStatus").asText()).isEqualTo("ACTIVE");
        assertThat(confirmed.get("priority").asText()).isEqualTo("HIGH");
        assertThat(confirmed.get("allocatedAmount").decimalValue()).isEqualByComparingTo("300.0000");
        assertThat(confirmed.get("allocationStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("projection").get("remainingGoalAmount").decimalValue())
                .isEqualByComparingTo("800.0000");
        assertThat(confirmed.get("projection").get("monthsToTarget").asInt()).isEqualTo(3);
        assertThat(confirmed.get("projection").get("estimatedAchievementDate").asText())
                .isEqualTo(CURRENT_MONTH.plusMonths(3).atEndOfMonth().toString());
        assertThat(confirmed.get("projection").get("reachable").asBoolean()).isTrue();
        assertThat(confirmed.get("projection").get("guaranteed").asBoolean()).isFalse();

        JsonNode cancelled = findAllocation(body, cancelledGoal);
        assertThat(cancelled.get("allocatedAmount").decimalValue()).isEqualByComparingTo("50.0000");
        assertThat(cancelled.get("allocationStatus").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("projection").isNull()).isTrue();
        assertThat(body.toString()).doesNotContain("userId", "planId", "monthlyPlanId");
    }

    @Test
    void fullyReplacesUpdatesCancelsReactivatesAndIdempotentlyPreservesAllocationRows() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID planId = insertPlan(user.userId(), CURRENT_MONTH, "TND", "700.0000");
        UUID firstGoal = createGoal(user, "TND", "1000.0000", "100.0000", 12);
        UUID secondGoal = createGoal(user, "TND", "1200.0000", "0.0000", 12);
        BigDecimal progressBefore = goalCurrentSaved(user, firstGoal);

        allocationPut(user, CURRENT_MONTH, "TND", allocationsJson(firstGoal, "200.0000", secondGoal, "300.0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalConfirmedAllocation").value(500.0));
        assertThat(count("goal_allocation", "monthly_plan_id = ?", planId)).isEqualTo(2);

        allocationPut(user, CURRENT_MONTH, "TND", allocationsJson(firstGoal, "250.0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalConfirmedAllocation").value(250.0));
        assertAllocation(planId, firstGoal, "CONFIRMED", "250.0000");
        assertAllocation(planId, secondGoal, "CANCELLED", null);

        allocationPut(user, CURRENT_MONTH, "TND", allocationsJson(secondGoal, "100.0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalConfirmedAllocation").value(100.0));
        assertAllocation(planId, firstGoal, "CANCELLED", null);
        assertAllocation(planId, secondGoal, "CONFIRMED", "100.0000");
        int version = allocationVersion(planId, secondGoal);

        allocationPut(user, CURRENT_MONTH, "TND", allocationsJson(secondGoal, "100.0000"))
                .andExpect(status().isOk());
        assertThat(count("goal_allocation", "monthly_plan_id = ?", planId)).isEqualTo(2);
        assertThat(allocationVersion(planId, secondGoal)).isEqualTo(version);

        allocationPut(user, CURRENT_MONTH, "TND", "{\"allocations\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalConfirmedAllocation").value(0.0))
                .andExpect(jsonPath("$.remainingUnallocatedCapacity").value(700.0));
        assertAllocation(planId, firstGoal, "CANCELLED", null);
        assertAllocation(planId, secondGoal, "CANCELLED", null);
        assertThat(count("goal_contribution", "financial_goal_id IN (?, ?)", firstGoal, secondGoal)).isZero();
        assertThat(goalCurrentSaved(user, firstGoal)).isEqualByComparingTo(progressBefore);
        assertAudit(user.userId(), "GOAL_ALLOCATIONS_REPLACED", planId);
    }

    @Test
    void rejectsInvalidForeignCurrencyInactiveDuplicateAndUnexpectedAllocationInputsAtomically() throws Exception {
        AppRegistration owner = registerAndActivate();
        AppRegistration other = registerAndActivate();
        UUID planId = insertPlan(owner.userId(), CURRENT_MONTH, "TND", "1000.0000");
        UUID validGoal = createGoal(owner, "TND", "1000.0000", "0.0000", 12);
        UUID usdGoal = createGoal(owner, "USD", "1000.0000", "0.0000", 12);
        UUID pausedGoal = createGoal(owner, "TND", "1000.0000", "0.0000", 12);
        UUID otherGoal = createGoal(other, "TND", "1000.0000", "0.0000", 12);
        changeStatus(owner, pausedGoal, "PAUSED").andExpect(status().isOk());

        allocationPut(owner, CURRENT_MONTH, "TND", allocationsJson(otherGoal, "10.0000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GOAL_NOT_FOUND"));
        allocationPut(owner, CURRENT_MONTH, "TND", allocationsJson(usdGoal, "10.0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GOAL_ALLOCATION"));
        allocationPut(owner, CURRENT_MONTH, "TND", allocationsJson(pausedGoal, "10.0000"))
                .andExpect(status().isConflict());
        allocationPut(owner, CURRENT_MONTH, "TND", """
                {"allocations":[
                  {"goalId":"%s","amount":10.0000},
                  {"goalId":"%s","amount":20.0000}
                ]}
                """.formatted(validGoal, validGoal))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GOAL_ALLOCATION"));

        for (String amount : new String[] {"-1.0000", "0.0000", "1.00001"}) {
            allocationPut(owner, CURRENT_MONTH, "TND", allocationsJson(validGoal, amount))
                    .andExpect(status().isBadRequest());
        }
        allocationPut(owner, CURRENT_MONTH, "TND", """
                {"allocations":[{"goalId":"%s","amount":10.0000,"status":"CONFIRMED"}]}
                """.formatted(validGoal))
                .andExpect(status().isBadRequest());

        assertThat(count("goal_allocation", "monthly_plan_id = ?", planId)).isZero();
    }

    @Test
    void enforcesExactPlanCapacityNonNegativeCapacityAndPastReadOnlyRulesWithoutPartialWrites() throws Exception {
        AppRegistration missingPlanUser = registerAndActivate();
        allocationPut(missingPlanUser, CURRENT_MONTH, "TND", "{\"allocations\":[]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOAL_ALLOCATION_PLAN_REQUIRED"));

        AppRegistration user = registerAndActivate();
        UUID goalId = createGoal(user, "TND", "1000.0000", "0.0000", 12);
        UUID zeroPlan = insertPlan(user.userId(), CURRENT_MONTH, "TND", "0.0000");
        allocationPut(user, CURRENT_MONTH, "TND", allocationsJson(goalId, "1.0000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOAL_ALLOCATION_CAPACITY_EXCEEDED"));
        allocationPut(user, CURRENT_MONTH, "TND", "{\"allocations\":[]}")
                .andExpect(status().isOk());
        assertThat(count("goal_allocation", "monthly_plan_id = ?", zeroPlan)).isZero();

        YearMonth future = CURRENT_MONTH.plusMonths(1);
        UUID negativePlan = insertPlan(user.userId(), future, "TND", "-50.0000");
        allocationPut(user, future, "TND", allocationsJson(goalId, "1.0000"))
                .andExpect(status().isConflict());
        allocationPut(user, future, "TND", "{\"allocations\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salaryPlanSavingCapacity").value(-50.0))
                .andExpect(jsonPath("$.allocatableCapacity").value(0.0));
        assertThat(count("goal_allocation", "monthly_plan_id = ?", negativePlan)).isZero();

        AppRegistration cappedUser = registerAndActivate();
        UUID cappedGoal = createGoal(cappedUser, "TND", "1000.0000", "0.0000", 12);
        UUID cappedPlan = insertPlan(cappedUser.userId(), CURRENT_MONTH, "TND", "100.0000");
        allocationPut(cappedUser, CURRENT_MONTH, "TND", allocationsJson(cappedGoal, "100.0001"))
                .andExpect(status().isConflict());
        assertThat(count("goal_allocation", "monthly_plan_id = ?", cappedPlan)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> insertAllocation(cappedPlan, cappedGoal, "100.0001", "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("goal_allocation", "monthly_plan_id = ?", cappedPlan)).isZero();

        YearMonth past = CURRENT_MONTH.minusMonths(1);
        insertPlan(user.userId(), past, "TND", "100.0000");
        allocationPut(user, past, "TND", "{\"allocations\":[]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOAL_ALLOCATION_PAST_MONTH"));
    }

    @Test
    void simulatesContributionDerivedProgressAndTargetDateWithoutPersistingAnything() throws Exception {
        AppRegistration owner = registerAndActivate();
        AppRegistration other = registerAndActivate();
        UUID goalId = createGoal(owner, "TND", "1000.0000", "100.0000", 4);
        insertContribution(goalId, "200.0000");
        UUID otherGoal = createGoal(other, "TND", "1000.0000", "0.0000", 12);
        UUID pausedGoal = createGoal(owner, "TND", "1000.0000", "0.0000", 12);
        changeStatus(owner, pausedGoal, "PAUSED").andExpect(status().isOk());
        int scenarioCount = count("goal_scenario", "TRUE");
        int allocationCount = count("goal_allocation", "TRUE");
        int contributionCount = count("goal_contribution", "financial_goal_id = ?", goalId);
        int auditCount = count("audit_log_entry", "actor_id = ?", owner.userId());

        simulation(owner, goalId, "200.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalId").value(goalId.toString()))
                .andExpect(jsonPath("$.currency").value("TND"))
                .andExpect(jsonPath("$.calculationDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.currentSaved").value(300.0))
                .andExpect(jsonPath("$.remainingAmount").value(700.0))
                .andExpect(jsonPath("$.simulatedMonthlyContribution").value(200.0))
                .andExpect(jsonPath("$.monthsToTarget").value(4))
                .andExpect(jsonPath("$.estimatedAchievementDate")
                        .value(CURRENT_MONTH.plusMonths(4).atEndOfMonth().toString()))
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.meetsTargetDate").value(true))
                .andExpect(jsonPath("$.method").value("DETERMINISTIC_RECURRING_CONTRIBUTION"))
                .andExpect(jsonPath("$.methodVersion").value("1"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.planId").doesNotExist());

        simulation(owner, goalId, "0.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(false))
                .andExpect(jsonPath("$.monthsToTarget").doesNotExist())
                .andExpect(jsonPath("$.estimatedAchievementDate").doesNotExist())
                .andExpect(jsonPath("$.meetsTargetDate").value(false));
        simulation(owner, goalId, "100.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthsToTarget").value(7))
                .andExpect(jsonPath("$.meetsTargetDate").value(false));

        simulation(owner, otherGoal, "10.0000")
                .andExpect(status().isNotFound());
        simulation(owner, pausedGoal, "10.0000")
                .andExpect(status().isConflict());
        simulation(owner, goalId, "-1.0000").andExpect(status().isBadRequest());
        simulation(owner, goalId, "1.00001").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/goals/{goalId}/simulations", goalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyContribution\":10.0000,\"currentSaved\":999.0000}"))
                .andExpect(status().isBadRequest());

        UUID completedGoal = createGoal(owner, "TND", "50.0000", "0.0000", 12);
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "financial_goal SET initial_amount = target_amount, status = 'ACTIVE' WHERE id = ?",
                completedGoal);
        simulation(owner, completedGoal, "0.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAmount").value(0.0))
                .andExpect(jsonPath("$.monthsToTarget").value(0))
                .andExpect(jsonPath("$.estimatedAchievementDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.reachable").value(true));

        assertThat(count("goal_scenario", "TRUE")).isEqualTo(scenarioCount);
        assertThat(count("goal_allocation", "TRUE")).isEqualTo(allocationCount);
        assertThat(count("goal_contribution", "financial_goal_id = ?", goalId)).isEqualTo(contributionCount);
        assertThat(count("audit_log_entry", "actor_id = ?", owner.userId())).isEqualTo(auditCount + 1);
    }

    private org.springframework.test.web.servlet.ResultActions allocationGet(
            AppRegistration user,
            YearMonth month,
            String currency) throws Exception {
        return mockMvc.perform(get("/api/goals/allocations/{month}", month)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .param("currency", currency));
    }

    private org.springframework.test.web.servlet.ResultActions allocationPut(
            AppRegistration user,
            YearMonth month,
            String currency,
            String content) throws Exception {
        return mockMvc.perform(put("/api/goals/allocations/{month}", month)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .param("currency", currency)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
    }

    private org.springframework.test.web.servlet.ResultActions simulation(
            AppRegistration user,
            UUID goalId,
            String monthlyContribution) throws Exception {
        return mockMvc.perform(post("/api/goals/{goalId}/simulations", goalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"monthlyContribution\":" + monthlyContribution + "}"));
    }

    private String allocationsJson(UUID goalId, String amount) {
        return "{\"allocations\":[{\"goalId\":\"" + goalId + "\",\"amount\":" + amount + "}]}";
    }

    private String allocationsJson(UUID firstGoal, String firstAmount, UUID secondGoal, String secondAmount) {
        return "{\"allocations\":[{\"goalId\":\"" + firstGoal + "\",\"amount\":" + firstAmount
                + "},{\"goalId\":\"" + secondGoal + "\",\"amount\":" + secondAmount + "}]}";
    }

    private UUID createGoal(
            AppRegistration user,
            String currency,
            String target,
            String initial,
            int targetMonths) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/goals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Objectif test",
                                  "description":"Test allocation",
                                  "type":"OTHER",
                                  "currency":"%s",
                                  "targetAmount":%s,
                                  "initialAmount":%s,
                                  "targetDate":"%s",
                                  "priority":"HIGH"
                                }
                                """.formatted(
                                currency,
                                target,
                                initial,
                                CURRENT_MONTH.plusMonths(targetMonths).atEndOfMonth())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions changeStatus(
            AppRegistration user,
            UUID goalId,
            String requestedStatus) throws Exception {
        return mockMvc.perform(patch("/api/goals/{goalId}/status", goalId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + requestedStatus + "\"}"));
    }

    private BigDecimal goalCurrentSaved(AppRegistration user, UUID goalId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/goals/{goalId}", goalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("currentSaved").decimalValue();
    }

    private UUID insertPlan(UUID userId, YearMonth month, String currency, String capacity) {
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "monthly_plan "
                        + "(id, user_id, period_month, currency, calculated_saving_capacity, "
                        + "calculation_method, method_version) VALUES (?, ?, ?, ?, ?, 'DETERMINISTIC', '1')",
                planId,
                userId,
                month.atDay(1),
                currency,
                new BigDecimal(capacity));
        return planId;
    }

    private void insertAllocation(UUID planId, UUID goalId, String amount, String allocationStatus) {
        boolean confirmed = allocationStatus.equals("CONFIRMED");
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_allocation "
                        + "(id, monthly_plan_id, financial_goal_id, planned_amount, confirmed_amount, status, confirmed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                planId,
                goalId,
                new BigDecimal(amount),
                confirmed ? new BigDecimal(amount) : null,
                allocationStatus,
                confirmed ? Timestamp.from(TEST_INSTANT) : null);
    }

    private void insertContribution(UUID goalId, String amount) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_contribution "
                        + "(id, financial_goal_id, source, amount, contribution_date) "
                        + "VALUES (?, ?, 'MANUAL', ?, ?)",
                UUID.randomUUID(),
                goalId,
                new BigDecimal(amount),
                TODAY.minusDays(1));
    }

    private void assertAllocation(UUID planId, UUID goalId, String expectedStatus, String confirmedAmount) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, planned_amount, confirmed_amount FROM " + SCHEMA
                        + "goal_allocation WHERE monthly_plan_id = ? AND financial_goal_id = ?",
                planId,
                goalId);
        assertThat(row.get("status")).isEqualTo(expectedStatus);
        if (confirmedAmount == null) {
            assertThat(row.get("confirmed_amount")).isNull();
        } else {
            assertThat((BigDecimal) row.get("planned_amount")).isEqualByComparingTo(confirmedAmount);
            assertThat((BigDecimal) row.get("confirmed_amount")).isEqualByComparingTo(confirmedAmount);
        }
    }

    private int allocationVersion(UUID planId, UUID goalId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM " + SCHEMA
                        + "goal_allocation WHERE monthly_plan_id = ? AND financial_goal_id = ?",
                Integer.class,
                planId,
                goalId);
    }

    private JsonNode findAllocation(JsonNode overview, UUID goalId) {
        for (JsonNode allocation : overview.get("allocations")) {
            if (goalId.toString().equals(allocation.get("goalId").asText())) {
                return allocation;
            }
        }
        throw new AssertionError("Allocation was not returned for goal " + goalId);
    }

    private int count(String table, String condition, Object... arguments) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + table + " WHERE " + condition,
                Integer.class,
                arguments);
    }

    private void assertAudit(UUID userId, String action, UUID resourceId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "audit_log_entry "
                        + "WHERE actor_id = ? AND action = ? AND resource_id = ? AND change_summary = '{}'::jsonb",
                Integer.class,
                userId,
                action,
                resourceId)).isPositive();
    }

    private AppRegistration registerAndActivate() throws Exception {
        String email = "goal-allocation-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"%s",
                                  "password":"StrongPass123!",
                                  "firstName":"Goals",
                                  "lastName":"Tester",
                                  "displayName":"Goals Tester"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json(result);
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        jdbcTemplate.update("UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbcTemplate.update(
                "UPDATE " + SCHEMA
                        + "authentication_identity SET status = 'ACTIVE', verified_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ?",
                userId);
        return new AppRegistration(userId, body.get("accessToken").asText());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
        Clock goalAllocationTestClock() {
            return Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
        }
    }
}
