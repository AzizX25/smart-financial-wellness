package com.azizx25.smartfinancialwellness.goal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.goal.GoalCalculator.GoalProgress;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalContributionRequest;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalContributionResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalResponse;
import com.azizx25.smartfinancialwellness.goal.api.GoalDtos.GoalWriteRequest;
import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoal;
import com.azizx25.smartfinancialwellness.goal.persistence.FinancialGoalRepository;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocation;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocationRepository;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContribution;
import com.azizx25.smartfinancialwellness.goal.persistence.GoalContributionRepository;
import com.azizx25.smartfinancialwellness.salary.MonetaryAmounts;
import com.azizx25.smartfinancialwellness.user.persistence.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoalService {

    private static final String RESOURCE_GOAL = "FINANCIAL_GOAL";
    private static final String RESOURCE_CONTRIBUTION = "GOAL_CONTRIBUTION";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MonetaryAmounts.SCALE);

    private final FinancialGoalRepository goalRepository;
    private final GoalContributionRepository contributionRepository;
    private final GoalAllocationRepository allocationRepository;
    private final AppUserRepository userRepository;
    private final GoalCalculator calculator;
    private final AuditService auditService;
    private final Clock clock;

    public GoalService(
            FinancialGoalRepository goalRepository,
            GoalContributionRepository contributionRepository,
            GoalAllocationRepository allocationRepository,
            AppUserRepository userRepository,
            GoalCalculator calculator,
            AuditService auditService,
            Clock clock) {
        this.goalRepository = goalRepository;
        this.contributionRepository = contributionRepository;
        this.allocationRepository = allocationRepository;
        this.userRepository = userRepository;
        this.calculator = calculator;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> list(UUID userId, String currency, boolean includeArchived) {
        String normalizedCurrency = MonetaryAmounts.requireCurrency(currency);
        LocalDate currentDate = LocalDate.now(clock);
        return goalRepository.findByUser_IdAndCurrencyOrderByCreatedAtAscIdAsc(userId, normalizedCurrency).stream()
                .filter(goal -> includeArchived || goal.getStatus() != FinancialGoal.Status.ARCHIVED)
                .map(goal -> response(goal, contributionsFor(goal, userId), currentDate))
                .toList();
    }

    @Transactional(readOnly = true)
    public GoalResponse get(UUID userId, UUID goalId) {
        FinancialGoal goal = ownedGoal(userId, goalId);
        return response(goal, contributionsFor(goal, userId), LocalDate.now(clock));
    }

    @Transactional
    public GoalResponse create(UUID userId, GoalWriteRequest request) {
        Objects.requireNonNull(userId, "userId is required");
        Instant now = clock.instant();
        LocalDate currentDate = LocalDate.now(clock);
        FinancialGoal goal;
        try {
            goal = new FinancialGoal(
                    userRepository.getReferenceById(userId),
                    request.name(),
                    request.description(),
                    request.type(),
                    request.currency(),
                    request.targetAmount(),
                    request.initialAmount(),
                    request.targetDate(),
                    request.priority(),
                    now);
            GoalProgress progress = progress(goal, List.of(), currentDate);
            goal.synchronizeAchievement(progress.remainingAmount().signum() == 0, now);
            goalRepository.save(goal);
            goalRepository.flush();
        } catch (IllegalArgumentException exception) {
            throw GoalException.invalid(exception.getMessage());
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw persistenceConflict();
        }
        auditService.record(userId, "GOAL_CREATED", RESOURCE_GOAL, goal.getId());
        return response(goal, List.of(), currentDate);
    }

    @Transactional
    public GoalResponse replace(UUID userId, UUID goalId, GoalWriteRequest request) {
        Instant now = clock.instant();
        LocalDate currentDate = LocalDate.now(clock);
        try {
            FinancialGoal goal = lockedGoalAfterAffectedPlans(userId, goalId, currentDate);
            requireEditable(goal);
            List<GoalContribution> contributions = contributionsFor(goal, userId);
            goal.replaceEditableFields(
                    request.name(),
                    request.description(),
                    request.type(),
                    request.currency(),
                    request.targetAmount(),
                    request.initialAmount(),
                    request.targetDate(),
                    request.priority(),
                    now);
            GoalProgress progress = progress(goal, contributions, currentDate);
            boolean statusChanged = goal.synchronizeAchievement(progress.remainingAmount().signum() == 0, now);
            if (statusChanged && goal.getStatus() == FinancialGoal.Status.ACHIEVED) {
                cancelCurrentAndFutureAllocations(goal, userId, now, currentDate);
            }
            goalRepository.flush();
            auditService.record(userId, "GOAL_UPDATED", RESOURCE_GOAL, goalId);
            recordAutomaticStatusChange(userId, goal, statusChanged);
            return response(goal, contributions, currentDate);
        } catch (IllegalArgumentException exception) {
            throw GoalException.invalid(exception.getMessage());
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public GoalResponse changeStatus(UUID userId, UUID goalId, FinancialGoal.Status requestedStatus) {
        Instant now = clock.instant();
        LocalDate currentDate = LocalDate.now(clock);
        try {
            FinancialGoal goal = lockedGoalAfterAffectedPlans(userId, goalId, currentDate);
            FinancialGoal.Status currentStatus = goal.getStatus();
            if (currentStatus == requestedStatus) {
                return response(goal, contributionsFor(goal, userId), currentDate);
            }
            if (!allowedTransition(currentStatus, requestedStatus)) {
                throw GoalException.invalidTransition();
            }
            goal.changeStatus(requestedStatus, now);
            if (requestedStatus != FinancialGoal.Status.ACTIVE) {
                cancelCurrentAndFutureAllocations(goal, userId, now, currentDate);
            }
            goalRepository.flush();
            auditService.record(userId, "GOAL_STATUS_CHANGED", RESOURCE_GOAL, goalId);
            return response(goal, contributionsFor(goal, userId), currentDate);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public void archive(UUID userId, UUID goalId) {
        Instant now = clock.instant();
        LocalDate currentDate = LocalDate.now(clock);
        try {
            FinancialGoal goal = lockedGoalAfterAffectedPlans(userId, goalId, currentDate);
            if (!goal.archive(now)) {
                return;
            }
            cancelCurrentAndFutureAllocations(goal, userId, now, currentDate);
            goalRepository.flush();
            auditService.record(userId, "GOAL_ARCHIVED", RESOURCE_GOAL, goalId);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional(readOnly = true)
    public List<GoalContributionResponse> contributions(UUID userId, UUID goalId) {
        ownedGoal(userId, goalId);
        return contributionRepository
                .findByFinancialGoal_IdAndFinancialGoal_User_IdOrderByContributionDateDescCreatedAtDescIdDesc(
                        goalId,
                        userId)
                .stream()
                .map(this::contributionResponse)
                .toList();
    }

    @Transactional
    public GoalResponse addContribution(UUID userId, UUID goalId, GoalContributionRequest request) {
        Instant now = clock.instant();
        LocalDate currentDate = LocalDate.now(clock);
        LocalDate contributionDate = request.contributionDate() == null
                ? currentDate
                : request.contributionDate();
        if (contributionDate.isAfter(currentDate)) {
            throw GoalException.invalid("Contribution date cannot be in the future");
        }
        try {
            FinancialGoal goal = lockedGoalAfterAffectedPlans(userId, goalId, currentDate);
            if (goal.getStatus() != FinancialGoal.Status.ACTIVE) {
                throw GoalException.conflict("Only active goals accept new contributions");
            }
            GoalContribution contribution = new GoalContribution(
                    goal,
                    GoalContribution.Source.MANUAL,
                    null,
                    request.amount(),
                    contributionDate,
                    null,
                    now);
            contributionRepository.save(contribution);
            contributionRepository.flush();
            List<GoalContribution> contributions = contributionsFor(goal, userId);
            GoalProgress progress = progress(goal, contributions, currentDate);
            boolean statusChanged = goal.synchronizeAchievement(progress.remainingAmount().signum() == 0, now);
            if (statusChanged && goal.getStatus() == FinancialGoal.Status.ACHIEVED) {
                cancelCurrentAndFutureAllocations(goal, userId, now, currentDate);
            }
            goalRepository.flush();
            auditService.record(userId, "GOAL_CONTRIBUTION_CREATED", RESOURCE_CONTRIBUTION, contribution.getId());
            recordAutomaticStatusChange(userId, goal, statusChanged);
            return response(goal, contributions, currentDate);
        } catch (IllegalArgumentException exception) {
            throw GoalException.invalid(exception.getMessage());
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public GoalResponse deleteContribution(UUID userId, UUID goalId, UUID contributionId) {
        Instant now = clock.instant();
        LocalDate currentDate = LocalDate.now(clock);
        try {
            FinancialGoal goal = lockedGoal(userId, goalId);
            requireEditable(goal);
            GoalContribution contribution = contributionRepository
                    .findOwnedForUpdate(contributionId, goalId, userId)
                    .orElseThrow(GoalException::contributionNotFound);
            if (contribution.getSource() != GoalContribution.Source.MANUAL) {
                throw GoalException.conflict("Transaction-linked contributions cannot be deleted manually");
            }
            contributionRepository.delete(contribution);
            contributionRepository.flush();
            List<GoalContribution> contributions = contributionsFor(goal, userId);
            GoalProgress progress = progress(goal, contributions, currentDate);
            boolean statusChanged = goal.synchronizeAchievement(progress.remainingAmount().signum() == 0, now);
            goalRepository.flush();
            auditService.record(
                    userId,
                    "GOAL_CONTRIBUTION_DELETED",
                    RESOURCE_CONTRIBUTION,
                    contributionId);
            recordAutomaticStatusChange(userId, goal, statusChanged);
            return response(goal, contributions, currentDate);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw persistenceConflict();
        }
    }

    private FinancialGoal ownedGoal(UUID userId, UUID goalId) {
        return goalRepository.findByIdAndUser_Id(goalId, userId).orElseThrow(GoalException::notFound);
    }

    private FinancialGoal lockedGoal(UUID userId, UUID goalId) {
        return goalRepository.findOwnedForUpdate(goalId, userId).orElseThrow(GoalException::notFound);
    }

    private FinancialGoal lockedGoalAfterAffectedPlans(UUID userId, UUID goalId, LocalDate currentDate) {
        LocalDate firstMonth = YearMonth.from(currentDate).atDay(1);
        allocationRepository.lockAffectedMonthlyPlans(goalId, userId, firstMonth);
        return lockedGoal(userId, goalId);
    }

    private void requireEditable(FinancialGoal goal) {
        if (goal.getStatus() == FinancialGoal.Status.CANCELLED
                || goal.getStatus() == FinancialGoal.Status.ARCHIVED) {
            throw GoalException.readOnly();
        }
    }

    private boolean allowedTransition(FinancialGoal.Status current, FinancialGoal.Status requested) {
        return (current == FinancialGoal.Status.ACTIVE
                    && (requested == FinancialGoal.Status.PAUSED
                        || requested == FinancialGoal.Status.CANCELLED))
                || (current == FinancialGoal.Status.PAUSED
                    && (requested == FinancialGoal.Status.ACTIVE
                        || requested == FinancialGoal.Status.CANCELLED));
    }

    private void cancelCurrentAndFutureAllocations(
            FinancialGoal goal,
            UUID userId,
            Instant now,
            LocalDate currentDate) {
        LocalDate firstMonth = YearMonth.from(currentDate).atDay(1);
        List<GoalAllocation> allocations = allocationRepository.findCurrentAndFutureForUpdate(
                goal.getId(),
                userId,
                firstMonth);
        allocations.forEach(allocation -> allocation.cancel(now));
        allocationRepository.flush();
    }

    private List<GoalContribution> contributionsFor(FinancialGoal goal, UUID userId) {
        return contributionRepository
                .findByFinancialGoal_IdAndFinancialGoal_User_IdOrderByContributionDateDescCreatedAtDescIdDesc(
                        goal.getId(),
                        userId);
    }

    private GoalProgress progress(
            FinancialGoal goal,
            List<GoalContribution> contributions,
            LocalDate currentDate) {
        return calculator.calculateProgress(
                goal.getTargetAmount(),
                goal.getInitialAmount(),
                contributions.stream().map(GoalContribution::getAmount).toList(),
                ZERO,
                goal.getTargetDate(),
                currentDate);
    }

    private GoalResponse response(
            FinancialGoal goal,
            List<GoalContribution> contributions,
            LocalDate currentDate) {
        GoalProgress progress = progress(goal, contributions, currentDate);
        BigDecimal contributionTotal = MonetaryAmounts.calculated(contributions.stream()
                .map(GoalContribution::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new GoalResponse(
                goal.getId(),
                goal.getName(),
                goal.getDescription(),
                goal.getGoalType(),
                goal.getCurrency(),
                goal.getTargetAmount(),
                goal.getInitialAmount(),
                contributionTotal,
                progress.currentSaved(),
                progress.remainingAmount(),
                progress.progressPercentage(),
                goal.getTargetDate(),
                progress.overdue(),
                goal.getPriority(),
                goal.getStatus(),
                goal.getCreatedAt(),
                goal.getUpdatedAt(),
                goal.getVersion());
    }

    private GoalContributionResponse contributionResponse(GoalContribution contribution) {
        return new GoalContributionResponse(
                contribution.getId(),
                contribution.getAmount(),
                contribution.getContributionDate(),
                contribution.getSource(),
                contribution.getCreatedAt());
    }

    private void recordAutomaticStatusChange(UUID userId, FinancialGoal goal, boolean changed) {
        if (changed) {
            auditService.record(userId, "GOAL_STATUS_CHANGED", RESOURCE_GOAL, goal.getId());
        }
    }

    private GoalException persistenceConflict() {
        return GoalException.conflict("The goal could not be updated because its data changed or is constrained");
    }
}
