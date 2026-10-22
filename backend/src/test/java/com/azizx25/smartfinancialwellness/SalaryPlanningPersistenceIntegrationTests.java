package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.salary.SalaryPlanCalculator;
import com.azizx25.smartfinancialwellness.salary.persistence.BudgetAllocation;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlan;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlanRepository;
import com.azizx25.smartfinancialwellness.salary.persistence.PlannedIncome;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SalaryPlanningPersistenceIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";

    @Autowired AppUserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired MonthlyPlanRepository planRepository;
    @Autowired SalaryPlanCalculator calculator;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @Test
    @Transactional
    void persistsAggregateWithMultipleIncomesManualOverrideAndNegativeCapacity() {
        AppUser user = createUser();
        Category food = systemCategory("FOOD");
        Instant now = Instant.parse("2026-08-01T09:00:00Z");
        MonthlyPlan plan = planRepository.saveAndFlush(new MonthlyPlan(user, YearMonth.of(2026, 8), "TND", now));

        plan.addIncome("Salaire", PlannedIncome.IncomeType.SALARY, new BigDecimal("80"), null, true, now);
        plan.addIncome("Mission", PlannedIncome.IncomeType.FREELANCE, new BigDecimal("20"), null, false, now);
        BudgetAllocation allocation = plan.addAllocation(
                food, new BigDecimal("120"), BudgetAllocation.RecommendationOrigin.RULES, now);
        allocation.confirm(new BigDecimal("150"), now.plusSeconds(1));
        var calculation = calculator.calculate(
                plan.getPlannedIncomes().stream().map(PlannedIncome::getExpectedAmount).toList(),
                plan.getBudgetAllocations().stream()
                        .map(item -> new SalaryPlanCalculator.AllocationAmount(
                                item.getRecommendedAmount(), item.getConfirmedAmount()))
                        .toList());
        plan.applyCalculation(calculation, "HISTORICAL_MONTHLY_AVERAGE", "1", now.plusSeconds(1));
        planRepository.saveAndFlush(plan);
        entityManager.clear();

        MonthlyPlan stored = planRepository.findById(plan.getId()).orElseThrow();
        assertThat(stored.getExpectedIncomeTotal()).isEqualByComparingTo("100.0000");
        assertThat(stored.getRecommendedExpenseBudget()).isEqualByComparingTo("120.0000");
        assertThat(stored.getCalculatedSavingCapacity()).isEqualByComparingTo("-50.0000");
        assertThat(stored.getPlannedIncomes()).hasSize(2);
        assertThat(stored.getBudgetAllocations()).singleElement().satisfies(item -> {
            assertThat(item.getRecommendedAmount()).isEqualByComparingTo("120.0000");
            assertThat(item.getConfirmedAmount()).isEqualByComparingTo("150.0000");
            assertThat(item.getEffectiveAmount()).isEqualByComparingTo("150.0000");
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "goal_allocation WHERE monthly_plan_id = ?",
                Integer.class,
                plan.getId())).isZero();
    }

    @Test
    void enforcesOnePlanPerUserMonthAndCurrencyWhileKeepingCurrenciesSeparate() {
        AppUser user = createUser();
        Instant now = Instant.parse("2026-08-01T09:00:00Z");
        planRepository.saveAndFlush(new MonthlyPlan(user, YearMonth.of(2026, 8), "TND", now));
        planRepository.saveAndFlush(new MonthlyPlan(user, YearMonth.of(2026, 8), "USD", now));

        assertThat(planRepository.findByUser_IdAndCurrencyOrderByPeriodMonthDesc(user.getId(), "TND"))
                .hasSize(1);
        assertThat(planRepository.findByUser_IdAndCurrencyOrderByPeriodMonthDesc(user.getId(), "USD"))
                .hasSize(1);
        assertThatThrownBy(() -> planRepository.saveAndFlush(
                new MonthlyPlan(user, YearMonth.of(2026, 8), "TND", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsInactiveNonExpenseAndForeignBudgetCategories() {
        AppUser owner = createUser();
        AppUser other = createUser();
        UUID planId = insertPlan(owner.getId(), "TND");
        Category foreign = categoryRepository.saveAndFlush(
                new Category(other, "Catégorie privée", Category.Nature.EXPENSE, null, null, Instant.now()));
        UUID inactive = insertInactiveExpenseCategory(owner.getId());

        insertAllocation(planId, systemCategory("FOOD").getId());
        assertThatThrownBy(() -> insertAllocation(planId, systemCategory("INCOME_SALARY").getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAllocation(planId, foreign.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAllocation(planId, inactive))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private AppUser createUser() {
        UUID id = UUID.randomUUID();
        return userRepository.saveAndFlush(new AppUser(id, "Salary", "Tester", "Salary " + id));
    }

    private Category systemCategory(String code) {
        return categoryRepository.findByCodeAndUserIsNull(code).orElseThrow();
    }

    private UUID insertPlan(UUID userId, String currency) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "monthly_plan "
                        + "(id, user_id, period_month, currency, calculation_method, method_version) "
                        + "VALUES (?, ?, DATE '2026-09-01', ?, 'DETERMINISTIC', '1')",
                id,
                userId,
                currency);
        return id;
    }

    private UUID insertInactiveExpenseCategory(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "category "
                        + "(id, user_id, code, label, taxonomy_version, direction, nature, active) "
                        + "VALUES (?, ?, ?, 'Inactive expense', 'user-v1', 'DEBIT', 'EXPENSE', FALSE)",
                id,
                userId,
                "INACTIVE_" + id.toString().replace("-", ""));
        return id;
    }

    private void insertAllocation(UUID planId, UUID categoryId) {
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "budget_allocation "
                        + "(id, monthly_plan_id, category_id, recommended_amount, recommendation_origin) "
                        + "VALUES (?, ?, ?, 10.0000, 'RULES')",
                UUID.randomUUID(),
                planId,
                categoryId);
    }
}
