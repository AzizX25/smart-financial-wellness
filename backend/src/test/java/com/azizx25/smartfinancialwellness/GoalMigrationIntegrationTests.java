package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class GoalMigrationIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void appliesV1ThroughV16AndKeepsProgressDerived() {
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "flyway_schema_history "
                        + "WHERE version IS NOT NULL AND success",
                Integer.class);
        Integer derivedProgressColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'smart_financial_wellness' "
                        + "AND table_name = 'financial_goal' "
                        + "AND column_name IN ('current_amount', 'current_saved', 'progress_percentage')",
                Integer.class);
        Integer identityTrigger = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger trigger "
                        + "JOIN pg_class relation ON relation.oid = trigger.tgrelid "
                        + "JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace "
                        + "WHERE namespace.nspname = 'smart_financial_wellness' "
                        + "AND trigger.tgname = 'trg_financial_goal_protect_identity' "
                        + "AND NOT trigger.tgisinternal",
                Integer.class);

        assertThat(successfulMigrations).isEqualTo(16);
        assertThat(derivedProgressColumns).isZero();
        assertThat(identityTrigger).isOne();
    }

    @Test
    void enforcesActiveGoalForLiveAllocationsButPreservesHistoricalRows() {
        UUID userId = createUser();
        UUID proposedPlan = createPlan(userId, "TND", "100", YearMonth.of(2026, 1));
        UUID proposedGoal = createGoal(userId, "TND", "ACTIVE");
        UUID proposedAllocation = insertAllocation(proposedPlan, proposedGoal, "PROPOSED", "50");

        updateGoalStatus(proposedGoal, "PAUSED");
        assertThat(allocationCount(proposedGoal)).isOne();
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "goal_allocation SET status = 'CANCELLED' WHERE id = ?",
                proposedAllocation);

        UUID confirmedPlan = createPlan(userId, "TND", "100", YearMonth.of(2026, 2));
        UUID confirmedGoal = createGoal(userId, "TND", "ACTIVE");
        insertAllocation(confirmedPlan, confirmedGoal, "CONFIRMED", "40");
        updateGoalStatus(confirmedGoal, "ACHIEVED");
        assertThat(allocationCount(confirmedGoal)).isOne();

        UUID pausedGoal = createGoal(userId, "TND", "PAUSED");
        UUID pausedPlan = createPlan(userId, "TND", "100", YearMonth.of(2026, 3));
        assertThatThrownBy(() -> insertAllocation(pausedPlan, pausedGoal, "PROPOSED", "10"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertAllocation(pausedPlan, pausedGoal, "CONFIRMED", "10"))
                .isInstanceOf(DataAccessException.class);
        insertAllocation(pausedPlan, pausedGoal, "CANCELLED", "10");
        assertThat(allocationCount(pausedGoal)).isOne();
    }

    @Test
    void enforcesAllocationUniquenessOwnershipAndCurrency() {
        UUID ownerId = createUser();
        UUID otherId = createUser();
        UUID goalId = createGoal(ownerId, "TND", "ACTIVE");
        UUID ownerPlan = createPlan(ownerId, "TND", "100", YearMonth.of(2026, 4));
        insertAllocation(ownerPlan, goalId, "PROPOSED", "10");

        assertThatThrownBy(() -> insertAllocation(ownerPlan, goalId, "PROPOSED", "20"))
                .isInstanceOf(DataAccessException.class);

        UUID otherPlan = createPlan(otherId, "TND", "100", YearMonth.of(2026, 4));
        assertThatThrownBy(() -> insertAllocation(otherPlan, goalId, "PROPOSED", "10"))
                .isInstanceOf(DataAccessException.class);

        UUID usdPlan = createPlan(ownerId, "USD", "100", YearMonth.of(2026, 4));
        assertThatThrownBy(() -> insertAllocation(usdPlan, goalId, "PROPOSED", "10"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void clampsCapacityAtZeroAndCountsOnlyConfirmedAllocations() {
        UUID userId = createUser();
        UUID deficitPlan = createPlan(userId, "TND", "-50", YearMonth.of(2026, 5));
        insertAllocation(deficitPlan, createGoal(userId, "TND", "ACTIVE"), "PROPOSED", "500");
        insertAllocation(deficitPlan, createGoal(userId, "TND", "PAUSED"), "CANCELLED", "500");
        insertAllocation(deficitPlan, createGoal(userId, "TND", "ACTIVE"), "CONFIRMED", "0");
        assertThatThrownBy(() -> insertAllocation(
                deficitPlan,
                createGoal(userId, "TND", "ACTIVE"),
                "CONFIRMED",
                "0.0001"))
                .isInstanceOf(DataAccessException.class);

        UUID positivePlan = createPlan(userId, "TND", "100", YearMonth.of(2026, 6));
        insertAllocation(positivePlan, createGoal(userId, "TND", "ACTIVE"), "PROPOSED", "1000");
        insertAllocation(positivePlan, createGoal(userId, "TND", "PAUSED"), "CANCELLED", "1000");
        insertAllocation(positivePlan, createGoal(userId, "TND", "ACTIVE"), "CONFIRMED", "100");
        assertThatThrownBy(() -> insertAllocation(
                positivePlan,
                createGoal(userId, "TND", "ACTIVE"),
                "CONFIRMED",
                "0.0001"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void makesOwnerImmutableAndFreezesCurrencyAfterEveryDependentRecordType() {
        UUID ownerId = createUser();
        UUID otherId = createUser();
        UUID unattachedGoal = createGoal(ownerId, "TND", "ACTIVE");

        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "financial_goal SET currency = 'USD' WHERE id = ?",
                unattachedGoal);
        assertThat(goalCurrency(unattachedGoal)).isEqualTo("USD");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "financial_goal SET user_id = ? WHERE id = ?",
                otherId,
                unattachedGoal))
                .isInstanceOf(DataAccessException.class);

        UUID allocationGoal = createGoal(ownerId, "TND", "ACTIVE");
        UUID planId = createPlan(ownerId, "TND", "100", YearMonth.of(2026, 7));
        insertAllocation(planId, allocationGoal, "PROPOSED", "10");
        assertCurrencyChangeRejected(allocationGoal);

        UUID contributionGoal = createGoal(ownerId, "TND", "ACTIVE");
        insertManualContribution(contributionGoal, "10");
        assertCurrencyChangeRejected(contributionGoal);

        UUID scenarioGoal = createGoal(ownerId, "TND", "ACTIVE");
        insertScenario(scenarioGoal);
        assertCurrencyChangeRejected(scenarioGoal);
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "app_user (id, display_name, status) VALUES (?, ?, 'ACTIVE')",
                id,
                "Goals migration user " + id);
        return id;
    }

    private UUID createPlan(
            UUID userId,
            String currency,
            String savingCapacity,
            YearMonth month) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "monthly_plan "
                        + "(id, user_id, period_month, currency, calculated_saving_capacity, "
                        + "calculation_method, method_version) "
                        + "VALUES (?, ?, ?, ?, ?, 'DETERMINISTIC', '1')",
                id,
                userId,
                Date.valueOf(month.atDay(1)),
                currency,
                new BigDecimal(savingCapacity));
        return id;
    }

    private UUID createGoal(UUID userId, String currency, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_goal "
                        + "(id, user_id, name, goal_type, currency, target_amount, status) "
                        + "VALUES (?, ?, ?, 'OTHER', ?, 1000, ?)",
                id,
                userId,
                "Goal " + id,
                currency,
                status);
        return id;
    }

    private UUID insertAllocation(UUID planId, UUID goalId, String status, String amount) {
        UUID id = UUID.randomUUID();
        if ("CONFIRMED".equals(status)) {
            jdbcTemplate.update(
                    "INSERT INTO " + SCHEMA + "goal_allocation "
                            + "(id, monthly_plan_id, financial_goal_id, planned_amount, confirmed_amount, "
                            + "status, confirmed_at) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', CURRENT_TIMESTAMP)",
                    id,
                    planId,
                    goalId,
                    new BigDecimal(amount),
                    new BigDecimal(amount));
        } else {
            jdbcTemplate.update(
                    "INSERT INTO " + SCHEMA + "goal_allocation "
                            + "(id, monthly_plan_id, financial_goal_id, planned_amount, status) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    id,
                    planId,
                    goalId,
                    new BigDecimal(amount),
                    status);
        }
        return id;
    }

    private void insertManualContribution(UUID goalId, String amount) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_contribution "
                        + "(id, financial_goal_id, source, amount, contribution_date) "
                        + "VALUES (?, ?, 'MANUAL', ?, DATE '2026-08-01')",
                UUID.randomUUID(),
                goalId,
                new BigDecimal(amount));
    }

    private void insertScenario(UUID goalId) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "goal_scenario "
                        + "(id, financial_goal_id, name, target_amount_snapshot, available_amount_snapshot, "
                        + "simulated_monthly_contribution, simulation_start_date, method_version) "
                        + "VALUES (?, ?, ?, 1000, 100, 50, DATE '2026-09-01', '1')",
                UUID.randomUUID(),
                goalId,
                "Scenario " + UUID.randomUUID());
    }

    private void updateGoalStatus(UUID goalId, String status) {
        jdbcTemplate.update(
                "UPDATE " + SCHEMA + "financial_goal SET status = ? WHERE id = ?",
                status,
                goalId);
    }

    private int allocationCount(UUID goalId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "goal_allocation WHERE financial_goal_id = ?",
                Integer.class,
                goalId);
    }

    private String goalCurrency(UUID goalId) {
        return jdbcTemplate.queryForObject(
                "SELECT currency FROM " + SCHEMA + "financial_goal WHERE id = ?",
                String.class,
                goalId);
    }

    private void assertCurrencyChangeRejected(UUID goalId) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "financial_goal SET currency = 'EUR' WHERE id = ?",
                goalId))
                .isInstanceOf(DataAccessException.class);
    }
}
