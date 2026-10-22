package com.azizx25.smartfinancialwellness.goal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Objects;

import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocation;
import com.azizx25.smartfinancialwellness.salary.MonetaryAmounts;
import org.springframework.stereotype.Component;

@Component
public class GoalCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MonetaryAmounts.SCALE);

    public GoalProgress calculateProgress(
            BigDecimal targetAmount,
            BigDecimal initialAmount,
            Collection<BigDecimal> contributionAmounts,
            BigDecimal monthlyContribution,
            LocalDate targetDate,
            LocalDate calculationDate) {
        BigDecimal target = requirePositive(targetAmount, "target amount");
        BigDecimal initial = MonetaryAmounts.requireNonNegativeInput(initialAmount, "initial amount");
        if (initial.compareTo(target) > 0) {
            throw new IllegalArgumentException("initial amount cannot exceed target amount");
        }
        Objects.requireNonNull(contributionAmounts, "contribution amounts are required");
        BigDecimal contributions = contributionAmounts.stream()
                .map(value -> MonetaryAmounts.requireNonNegativeInput(value, "contribution amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recurringContribution = MonetaryAmounts.requireNonNegativeInput(
                monthlyContribution,
                "monthly contribution");
        LocalDate calculatedOn = Objects.requireNonNull(calculationDate, "calculation date is required");

        BigDecimal currentSaved = MonetaryAmounts.calculated(initial.add(contributions));
        BigDecimal remaining = MonetaryAmounts.calculated(target.subtract(currentSaved).max(BigDecimal.ZERO));
        BigDecimal progressPercentage = currentSaved.multiply(ONE_HUNDRED)
                .divide(target, MonetaryAmounts.SCALE, RoundingMode.HALF_EVEN)
                .min(ONE_HUNDRED.setScale(MonetaryAmounts.SCALE));

        Integer monthsToTarget;
        LocalDate estimatedAchievementDate;
        if (remaining.signum() == 0) {
            monthsToTarget = 0;
            estimatedAchievementDate = calculatedOn;
        } else if (recurringContribution.signum() == 0) {
            monthsToTarget = null;
            estimatedAchievementDate = null;
        } else {
            monthsToTarget = remaining.divide(recurringContribution, 0, RoundingMode.CEILING).intValueExact();
            estimatedAchievementDate = YearMonth.from(calculatedOn)
                    .plusMonths(monthsToTarget)
                    .atEndOfMonth();
        }

        boolean overdue = targetDate != null && targetDate.isBefore(calculatedOn) && remaining.signum() > 0;
        return new GoalProgress(
                currentSaved,
                remaining,
                progressPercentage,
                monthsToTarget,
                estimatedAchievementDate,
                overdue);
    }

    public Capacity calculateCapacity(
            BigDecimal savingCapacity,
            Collection<AllocationAmount> allocations) {
        BigDecimal planCapacity = requireScale(savingCapacity, "saving capacity");
        Objects.requireNonNull(allocations, "allocations are required");
        BigDecimal available = MonetaryAmounts.calculated(planCapacity.max(BigDecimal.ZERO));
        BigDecimal allocated = allocations.stream()
                .map(this::confirmedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        allocated = MonetaryAmounts.calculated(allocated);
        return new Capacity(available, allocated, MonetaryAmounts.calculated(available.subtract(allocated)));
    }

    private BigDecimal confirmedAmount(AllocationAmount allocation) {
        Objects.requireNonNull(allocation, "allocation is required");
        BigDecimal amount = MonetaryAmounts.requireNonNegativeInput(allocation.amount(), "allocation amount");
        return allocation.status() == GoalAllocation.Status.CONFIRMED ? amount : BigDecimal.ZERO;
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        BigDecimal amount = MonetaryAmounts.requireNonNegativeInput(value, field);
        if (amount.signum() == 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return amount;
    }

    private BigDecimal requireScale(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.scale() > MonetaryAmounts.SCALE) {
            throw new IllegalArgumentException(field + " must have at most four fractional digits");
        }
        return value.setScale(MonetaryAmounts.SCALE);
    }

    public record AllocationAmount(GoalAllocation.Status status, BigDecimal amount) {
        public AllocationAmount {
            Objects.requireNonNull(status, "allocation status is required");
        }
    }

    public record Capacity(
            BigDecimal availableCapacity,
            BigDecimal allocatedAmount,
            BigDecimal unallocatedAmount) {
    }

    public record GoalProgress(
            BigDecimal currentSaved,
            BigDecimal remainingAmount,
            BigDecimal progressPercentage,
            Integer monthsToTarget,
            LocalDate estimatedAchievementDate,
            boolean overdue) {
    }
}
