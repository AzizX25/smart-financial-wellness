package com.azizx25.smartfinancialwellness.goal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.goal.GoalCalculator.AllocationAmount;
import com.azizx25.smartfinancialwellness.goal.GoalCalculator.Capacity;
import com.azizx25.smartfinancialwellness.goal.GoalCalculator.GoalProgress;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalAllocationItemResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalAllocationOverviewResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalAllocationReplaceRequest;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalAllocationRequest;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalProjectionResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalSimulationRequest;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalSimulationResponse;
import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoal;
import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoalRepository;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocation;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocationRepository;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContribution;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContributionRepository;
import com.azizx25.smartfinancialwellness.salary.MonetaryAmounts;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlan;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlanRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoalAllocationService {

    private static final String SIMULATION_METHOD = "DETERMINISTIC_RECURRING_CONTRIBUTION";
    private static final String SIMULATION_METHOD_VERSION = "1";
    private static final String RESOURCE_ALLOCATION = "GOAL_ALLOCATION";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MonetaryAmounts.SCALE);

    private final MonthlyPlanRepository planRepository;
    private final FinancialGoalRepository goalRepository;
    private final GoalAllocationRepository allocationRepository;
    private final GoalContributionRepository contributionRepository;
    private final GoalCalculator calculator;
    private final AuditService auditService;
    private final Clock clock;

    public GoalAllocationService(
            MonthlyPlanRepository planRepository,
            FinancialGoalRepository goalRepository,
            GoalAllocationRepository allocationRepository,
            GoalContributionRepository contributionRepository,
            GoalCalculator calculator,
            AuditService auditService,
            Clock clock) {
        this.planRepository = planRepository;
        this.goalRepository = goalRepository;
        this.allocationRepository = allocationRepository;
        this.contributionRepository = contributionRepository;
        this.calculator = calculator;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GoalAllocationOverviewResponse overview(UUID userId, YearMonth month, String currency) {
        Objects.requireNonNull(userId, "userId is required");
        YearMonth requestedMonth = Objects.requireNonNull(month, "month is required");
        String normalizedCurrency = MonetaryAmounts.requireCurrency(currency);
        return planRepository.findByUser_IdAndPeriodMonthAndCurrency(
                        userId,
                        requestedMonth.atDay(1),
                        normalizedCurrency)
                .map(plan -> overview(userId, requestedMonth, normalizedCurrency, plan))
                .orElseGet(() -> missingPlanOverview(requestedMonth, normalizedCurrency));
    }

    @Transactional
    public GoalAllocationOverviewResponse replace(
            UUID userId,
            YearMonth month,
            String currency,
            GoalAllocationReplaceRequest request) {
        Objects.requireNonNull(userId, "userId is required");
        YearMonth requestedMonth = Objects.requireNonNull(month, "month is required");
        String normalizedCurrency = MonetaryAmounts.requireCurrency(currency);
        Objects.requireNonNull(request, "request is required");
        if (requestedMonth.isBefore(YearMonth.from(LocalDate.now(clock)))) {
            throw GoalException.pastAllocationReadOnly();
        }

        try {
            MonthlyPlan plan = planRepository.findOwnedForUpdate(
                            userId,
                            requestedMonth.atDay(1),
                            normalizedCurrency)
                    .orElseThrow(GoalException::allocationPlanRequired);

            Map<UUID, GoalAllocationRequest> requested = uniqueRequests(request.allocations());
            Map<UUID, FinancialGoal> goals = lockRequestedGoals(userId, requested.keySet());
            List<GoalAllocation> existing = allocationRepository.findByOwnedMonthlyPlanForUpdate(
                    plan.getId(),
                    userId);

            validateReplacement(plan, normalizedCurrency, requested, goals);
            Instant now = clock.instant();
            Map<UUID, GoalAllocation> existingByGoal = new HashMap<>();
            existing.forEach(allocation -> existingByGoal.put(allocation.getFinancialGoalId(), allocation));

            existing.stream()
                    .filter(allocation -> !requested.containsKey(allocation.getFinancialGoalId()))
                    .filter(allocation -> allocation.getStatus() != GoalAllocation.Status.CANCELLED)
                    .forEach(allocation -> allocation.cancel(now));

            requested.forEach((goalId, item) -> {
                GoalAllocation allocation = existingByGoal.get(goalId);
                if (allocation == null) {
                    allocation = new GoalAllocation(plan, goals.get(goalId), item.amount(), now);
                    allocation.replaceAndConfirm(item.amount(), now);
                    allocationRepository.save(allocation);
                } else {
                    allocation.replaceAndConfirm(item.amount(), now);
                }
            });
            allocationRepository.flush();
            auditService.record(userId, "GOAL_ALLOCATIONS_REPLACED", RESOURCE_ALLOCATION, plan.getId());
            return overview(userId, requestedMonth, normalizedCurrency, plan);
        } catch (GoalException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw GoalException.invalidAllocation(exception.getMessage());
        } catch (DataIntegrityViolationException
                | ObjectOptimisticLockingFailureException
                | PessimisticLockingFailureException exception) {
            throw GoalException.allocationConflict();
        }
    }

    @Transactional(readOnly = true)
    public GoalSimulationResponse simulate(UUID userId, UUID goalId, GoalSimulationRequest request) {
        Objects.requireNonNull(request, "request is required");
        try {
            FinancialGoal goal = goalRepository.findByIdAndUser_Id(goalId, userId)
                    .orElseThrow(GoalException::notFound);
            if (goal.getStatus() != FinancialGoal.Status.ACTIVE) {
                throw GoalException.conflict("Only active goals can be simulated");
            }
            BigDecimal monthlyContribution = MonetaryAmounts.requireNonNegativeInput(
                    request.monthlyContribution(),
                    "monthly contribution");
            LocalDate calculationDate = LocalDate.now(clock);
            GoalProgress progress = calculator.calculateProgress(
                    goal.getTargetAmount(),
                    goal.getInitialAmount(),
                    contributionAmounts(goal, userId),
                    monthlyContribution,
                    goal.getTargetDate(),
                    calculationDate);
            boolean reachable = progress.monthsToTarget() != null;
            Boolean meetsTargetDate = goal.getTargetDate() == null
                    ? null
                    : reachable && !progress.estimatedAchievementDate().isAfter(goal.getTargetDate());
            return new GoalSimulationResponse(
                    goal.getId(),
                    goal.getCurrency(),
                    calculationDate,
                    progress.currentSaved(),
                    progress.remainingAmount(),
                    monthlyContribution,
                    progress.monthsToTarget(),
                    progress.estimatedAchievementDate(),
                    reachable,
                    goal.getTargetDate(),
                    meetsTargetDate,
                    SIMULATION_METHOD,
                    SIMULATION_METHOD_VERSION);
        } catch (IllegalArgumentException exception) {
            throw GoalException.invalid(exception.getMessage());
        }
    }

    private Map<UUID, GoalAllocationRequest> uniqueRequests(List<GoalAllocationRequest> items) {
        Objects.requireNonNull(items, "allocations are required");
        Map<UUID, GoalAllocationRequest> requested = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (GoalAllocationRequest item : items) {
            Objects.requireNonNull(item, "allocation is required");
            if (!seen.add(item.goalId())) {
                throw GoalException.invalidAllocation("Duplicate goal IDs are not allowed");
            }
            requested.put(item.goalId(), item);
        }
        return requested;
    }

    private Map<UUID, FinancialGoal> lockRequestedGoals(UUID userId, Set<UUID> goalIds) {
        Map<UUID, FinancialGoal> goals = new HashMap<>();
        goalIds.stream().sorted().forEach(goalId -> goals.put(
                goalId,
                goalRepository.findOwnedForUpdate(goalId, userId).orElseThrow(GoalException::notFound)));
        return goals;
    }

    private void validateReplacement(
            MonthlyPlan plan,
            String currency,
            Map<UUID, GoalAllocationRequest> requested,
            Map<UUID, FinancialGoal> goals) {
        BigDecimal total = BigDecimal.ZERO;
        for (GoalAllocationRequest item : requested.values()) {
            BigDecimal amount = MonetaryAmounts.requireNonNegativeInput(item.amount(), "allocation amount");
            if (amount.signum() == 0) {
                throw GoalException.invalidAllocation("Allocation amount must be positive");
            }
            FinancialGoal goal = goals.get(item.goalId());
            if (!goal.getCurrency().equals(currency)) {
                throw GoalException.invalidAllocation("Goal currency must match the Salary Plan currency");
            }
            if (goal.getStatus() != FinancialGoal.Status.ACTIVE) {
                throw GoalException.conflict("Only active goals can receive allocations");
            }
            total = total.add(amount);
        }
        BigDecimal allocatable = plan.getCalculatedSavingCapacity().max(BigDecimal.ZERO);
        if (total.compareTo(allocatable) > 0) {
            throw GoalException.allocationCapacityExceeded();
        }
    }

    private GoalAllocationOverviewResponse missingPlanOverview(YearMonth month, String currency) {
        return new GoalAllocationOverviewResponse(
                month.toString(),
                currency,
                false,
                null,
                ZERO,
                ZERO,
                ZERO,
                List.of());
    }

    private GoalAllocationOverviewResponse overview(
            UUID userId,
            YearMonth month,
            String currency,
            MonthlyPlan plan) {
        List<GoalAllocation> allocations = allocationRepository.findByOwnedMonthlyPlan(plan.getId(), userId);
        List<AllocationAmount> amounts = allocations.stream()
                .map(allocation -> new AllocationAmount(
                        allocation.getStatus(),
                        allocation.getStatus() == GoalAllocation.Status.CONFIRMED
                                ? allocation.getConfirmedAmount()
                                : allocation.getPlannedAmount()))
                .toList();
        Capacity capacity = calculator.calculateCapacity(plan.getCalculatedSavingCapacity(), amounts);
        List<GoalAllocationItemResponse> items = new ArrayList<>();
        for (GoalAllocation allocation : allocations) {
            FinancialGoal goal = allocation.getFinancialGoal();
            BigDecimal allocatedAmount = allocation.getStatus() == GoalAllocation.Status.CONFIRMED
                    ? allocation.getConfirmedAmount()
                    : allocation.getPlannedAmount();
            items.add(new GoalAllocationItemResponse(
                    goal.getId(),
                    goal.getName(),
                    goal.getStatus(),
                    goal.getPriority(),
                    allocatedAmount,
                    allocation.getStatus(),
                    projection(goal, allocation, userId)));
        }
        return new GoalAllocationOverviewResponse(
                month.toString(),
                currency,
                true,
                plan.getCalculatedSavingCapacity(),
                capacity.availableCapacity(),
                capacity.allocatedAmount(),
                capacity.unallocatedAmount(),
                List.copyOf(items));
    }

    private GoalProjectionResponse projection(
            FinancialGoal goal,
            GoalAllocation allocation,
            UUID userId) {
        if (allocation.getStatus() != GoalAllocation.Status.CONFIRMED
                || goal.getStatus() != FinancialGoal.Status.ACTIVE) {
            return null;
        }
        GoalProgress progress = calculator.calculateProgress(
                goal.getTargetAmount(),
                goal.getInitialAmount(),
                contributionAmounts(goal, userId),
                allocation.getConfirmedAmount(),
                goal.getTargetDate(),
                LocalDate.now(clock));
        return new GoalProjectionResponse(
                progress.remainingAmount(),
                progress.monthsToTarget(),
                progress.estimatedAchievementDate(),
                progress.monthsToTarget() != null,
                false);
    }

    private List<BigDecimal> contributionAmounts(FinancialGoal goal, UUID userId) {
        return contributionRepository
                .findByFinancialGoal_IdAndFinancialGoal_User_IdOrderByContributionDateAscCreatedAtAsc(
                        goal.getId(),
                        userId)
                .stream()
                .map(GoalContribution::getAmount)
                .toList();
    }
}
