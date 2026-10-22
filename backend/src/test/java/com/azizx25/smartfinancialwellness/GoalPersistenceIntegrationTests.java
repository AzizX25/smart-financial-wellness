package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoal;
import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoalRepository;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocation;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocationRepository;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContribution;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContributionRepository;
import com.azizx25.smartfinancialwellness.salary.SalaryPlanCalculator;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlan;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlanRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class GoalPersistenceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");

    @Autowired AppUserRepository userRepository;
    @Autowired MonthlyPlanRepository monthlyPlanRepository;
    @Autowired FinancialGoalRepository goalRepository;
    @Autowired GoalAllocationRepository allocationRepository;
    @Autowired GoalContributionRepository contributionRepository;
    @Autowired SalaryPlanCalculator salaryCalculator;
    @Autowired EntityManager entityManager;

    @Test
    @Transactional
    void mapsGoalAllocationAndContributionWithoutPersistingDerivedProgress() {
        AppUser owner = createUser("Owner");
        MonthlyPlan plan = createPlan(owner, "TND", "500");
        FinancialGoal goal = goalRepository.save(goal(owner, "TND", "Emergency fund"));
        entityManager.flush();

        GoalContribution contribution = contributionRepository.save(new GoalContribution(
                goal,
                GoalContribution.Source.MANUAL,
                null,
                new BigDecimal("75.1250"),
                LocalDate.of(2026, 8, 10),
                "Initial transfer",
                NOW));
        GoalAllocation allocation = new GoalAllocation(plan, goal, new BigDecimal("120"), NOW);
        allocation.confirm(new BigDecimal("100"), NOW.plusSeconds(60));
        allocationRepository.save(allocation);
        entityManager.flush();
        entityManager.clear();

        FinancialGoal storedGoal = goalRepository.findByIdAndUser_Id(goal.getId(), owner.getId()).orElseThrow();
        List<GoalContribution> contributions = contributionRepository
                .findByFinancialGoal_IdAndFinancialGoal_User_IdOrderByContributionDateAscCreatedAtAsc(
                        goal.getId(), owner.getId());
        List<GoalAllocation> allocations = allocationRepository.findByOwnedMonthlyPlan(plan.getId(), owner.getId());

        assertThat(storedGoal.getInitialAmount()).isEqualByComparingTo("50.0000");
        assertThat(storedGoal.getTargetAmount()).isEqualByComparingTo("1000.0000");
        assertThat(storedGoal.getStatus()).isEqualTo(FinancialGoal.Status.ACTIVE);
        assertThat(storedGoal.getVersion()).isZero();
        assertThat(contributions).singleElement().satisfies(stored -> {
            assertThat(stored.getId()).isEqualTo(contribution.getId());
            assertThat(stored.getSource()).isEqualTo(GoalContribution.Source.MANUAL);
            assertThat(stored.getAmount()).isEqualByComparingTo("75.1250");
            assertThat(stored.getTransaction()).isNull();
        });
        assertThat(allocations).singleElement().satisfies(stored -> {
            assertThat(stored.getStatus()).isEqualTo(GoalAllocation.Status.CONFIRMED);
            assertThat(stored.getPlannedAmount()).isEqualByComparingTo("120.0000");
            assertThat(stored.getConfirmedAmount()).isEqualByComparingTo("100.0000");
            assertThat(stored.getMonthlyPlanId()).isEqualTo(plan.getId());
            assertThat(stored.getFinancialGoalId()).isEqualTo(goal.getId());
        });
    }

    @Test
    @Transactional
    void repositoryReadsAreScopedByOwnerAndCurrency() {
        AppUser owner = createUser("Owner");
        AppUser other = createUser("Other");
        MonthlyPlan plan = createPlan(owner, "TND", "100");
        FinancialGoal tndGoal = goalRepository.save(goal(owner, "TND", "TND goal"));
        goalRepository.save(goal(owner, "USD", "USD goal"));
        goalRepository.save(goal(other, "TND", "Other goal"));
        allocationRepository.save(new GoalAllocation(plan, tndGoal, new BigDecimal("10"), NOW));
        contributionRepository.save(new GoalContribution(
                tndGoal,
                GoalContribution.Source.MANUAL,
                null,
                BigDecimal.ONE,
                LocalDate.of(2026, 8, 1),
                null,
                NOW));
        entityManager.flush();

        assertThat(goalRepository.findByUser_IdAndCurrencyOrderByCreatedAtAscIdAsc(owner.getId(), "TND"))
                .extracting(FinancialGoal::getId)
                .containsExactly(tndGoal.getId());
        assertThat(goalRepository.findByIdAndUser_Id(tndGoal.getId(), other.getId())).isEmpty();
        assertThat(goalRepository.findOwnedForUpdate(tndGoal.getId(), other.getId())).isEmpty();
        assertThat(allocationRepository.findByOwnedMonthlyPlan(plan.getId(), other.getId())).isEmpty();
        assertThat(allocationRepository.findByOwnedMonthlyPlanForUpdate(plan.getId(), owner.getId()))
                .extracting(GoalAllocation::getFinancialGoalId)
                .containsExactly(tndGoal.getId());
        assertThat(contributionRepository
                .findByFinancialGoal_IdAndFinancialGoal_User_IdOrderByContributionDateAscCreatedAtAsc(
                        tndGoal.getId(), other.getId()))
                .isEmpty();
    }

    private AppUser createUser(String label) {
        UUID id = UUID.randomUUID();
        return userRepository.saveAndFlush(new AppUser(id, label, "Goals", label + " " + id));
    }

    private MonthlyPlan createPlan(AppUser owner, String currency, String savingCapacity) {
        MonthlyPlan plan = new MonthlyPlan(owner, YearMonth.of(2026, 8), currency, NOW);
        var calculation = salaryCalculator.calculate(
                List.of(new BigDecimal(savingCapacity)),
                List.of());
        plan.applyCalculation(calculation, "DETERMINISTIC", "1", NOW);
        return monthlyPlanRepository.saveAndFlush(plan);
    }

    private FinancialGoal goal(AppUser owner, String currency, String name) {
        return new FinancialGoal(
                owner,
                name,
                "A focused test goal",
                FinancialGoal.GoalType.OTHER,
                currency,
                new BigDecimal("1000"),
                new BigDecimal("50"),
                LocalDate.of(2027, 8, 31),
                FinancialGoal.Priority.HIGH,
                NOW);
    }
}
