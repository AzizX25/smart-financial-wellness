package com.azizx25.smartfinancialwellness.goal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.azizx25.smartfinancialwellness.goal.persistence.GoalAllocation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalCalculatorTests {

    private final GoalCalculator calculator = new GoalCalculator();

    @Test
    void derivesProgressFromInitialAmountAndContributions() {
        var result = calculator.calculateProgress(
                new BigDecimal("1000"),
                new BigDecimal("100"),
                List.of(new BigDecimal("25.1250"), new BigDecimal("74.8750")),
                new BigDecimal("100"),
                LocalDate.of(2027, 1, 31),
                LocalDate.of(2026, 8, 13));

        assertThat(result.currentSaved()).isEqualByComparingTo("200.0000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("800.0000");
        assertThat(result.progressPercentage()).isEqualByComparingTo("20.0000");
        assertThat(result.monthsToTarget()).isEqualTo(8);
        assertThat(result.estimatedAchievementDate()).isEqualTo(LocalDate.of(2027, 4, 30));
        assertThat(result.overdue()).isFalse();
    }

    @Test
    void roundsPercentageHalfEvenAndRoundsRequiredMonthsUp() {
        var result = calculator.calculateProgress(
                new BigDecimal("3"),
                BigDecimal.ZERO,
                List.of(BigDecimal.ONE),
                new BigDecimal("0.6"),
                null,
                LocalDate.of(2026, 8, 13));

        assertThat(result.progressPercentage()).isEqualByComparingTo("33.3333");
        assertThat(result.monthsToTarget()).isEqualTo(4);
        assertThat(result.estimatedAchievementDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void returnsNoEstimateForAnUnfinishedGoalWithoutMonthlyContribution() {
        var result = calculator.calculateProgress(
                new BigDecimal("100"),
                new BigDecimal("20"),
                List.of(),
                BigDecimal.ZERO,
                null,
                LocalDate.of(2026, 8, 13));

        assertThat(result.remainingAmount()).isEqualByComparingTo("80.0000");
        assertThat(result.monthsToTarget()).isNull();
        assertThat(result.estimatedAchievementDate()).isNull();
    }

    @Test
    void completedGoalHasZeroRemainingAndZeroMonths() {
        LocalDate calculationDate = LocalDate.of(2026, 8, 13);

        var exactlyCompleted = calculator.calculateProgress(
                new BigDecimal("100"),
                new BigDecimal("80"),
                List.of(new BigDecimal("20")),
                BigDecimal.ZERO,
                null,
                calculationDate);
        var result = calculator.calculateProgress(
                new BigDecimal("100"),
                new BigDecimal("80"),
                List.of(new BigDecimal("25")),
                BigDecimal.ZERO,
                LocalDate.of(2026, 7, 31),
                calculationDate);

        assertThat(exactlyCompleted.currentSaved()).isEqualByComparingTo("100.0000");
        assertThat(exactlyCompleted.remainingAmount()).isEqualByComparingTo("0.0000");
        assertThat(exactlyCompleted.progressPercentage()).isEqualByComparingTo("100.0000");
        assertThat(result.currentSaved()).isEqualByComparingTo("105.0000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("0.0000");
        assertThat(result.progressPercentage()).isEqualByComparingTo("100.0000");
        assertThat(result.monthsToTarget()).isZero();
        assertThat(result.estimatedAchievementDate()).isEqualTo(calculationDate);
        assertThat(result.overdue()).isFalse();
    }

    @Test
    void identifiesOnlyUnfinishedGoalsPastTheirTargetDateAsOverdue() {
        var result = calculator.calculateProgress(
                new BigDecimal("100"),
                BigDecimal.ZERO,
                List.of(),
                new BigDecimal("10"),
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 13));

        assertThat(result.overdue()).isTrue();
    }

    @Test
    void clampsCapacityAtZeroAndCountsOnlyConfirmedAllocations() {
        var positive = calculator.calculateCapacity(
                new BigDecimal("100"),
                List.of(
                        allocation(GoalAllocation.Status.PROPOSED, "80"),
                        allocation(GoalAllocation.Status.CONFIRMED, "25.125"),
                        allocation(GoalAllocation.Status.CANCELLED, "90")));
        var zero = calculator.calculateCapacity(BigDecimal.ZERO, List.of());
        var deficit = calculator.calculateCapacity(new BigDecimal("-50"), List.of());

        assertThat(positive.availableCapacity()).isEqualByComparingTo("100.0000");
        assertThat(positive.allocatedAmount()).isEqualByComparingTo("25.1250");
        assertThat(positive.unallocatedAmount()).isEqualByComparingTo("74.8750");
        assertThat(zero.availableCapacity()).isEqualByComparingTo("0.0000");
        assertThat(deficit.availableCapacity()).isEqualByComparingTo("0.0000");
        assertThat(deficit.unallocatedAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    void rejectsNegativeOrOverPreciseMonetaryInputs() {
        assertThatThrownBy(() -> calculator.calculateProgress(
                new BigDecimal("100.00001"),
                BigDecimal.ZERO,
                List.of(),
                BigDecimal.ZERO,
                null,
                LocalDate.of(2026, 8, 13)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("four fractional digits");
        assertThatThrownBy(() -> calculator.calculateProgress(
                new BigDecimal("100"),
                BigDecimal.ZERO,
                List.of(new BigDecimal("-0.0001")),
                BigDecimal.ZERO,
                null,
                LocalDate.of(2026, 8, 13)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> calculator.calculateCapacity(
                new BigDecimal("1.00001"),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("four fractional digits");
        assertThatThrownBy(() -> calculator.calculateCapacity(
                BigDecimal.ZERO,
                List.of(allocation(GoalAllocation.Status.CONFIRMED, "1.00001"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("four fractional digits");
    }

    private GoalCalculator.AllocationAmount allocation(GoalAllocation.Status status, String amount) {
        return new GoalCalculator.AllocationAmount(status, new BigDecimal(amount));
    }
}
