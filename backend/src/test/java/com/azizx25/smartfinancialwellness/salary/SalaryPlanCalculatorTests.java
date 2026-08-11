package com.azizx25.smartfinancialwellness.salary;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalaryPlanCalculatorTests {

    private final SalaryPlanCalculator calculator = new SalaryPlanCalculator();

    @Test
    void sumsMultipleIncomesAndUsesConfirmedAmountsWithoutReplacingRecommendations() {
        var result = calculator.calculate(
                List.of(new BigDecimal("2500"), new BigDecimal("500.125")),
                List.of(
                        new SalaryPlanCalculator.AllocationAmount(new BigDecimal("1200"), null),
                        new SalaryPlanCalculator.AllocationAmount(new BigDecimal("800"), new BigDecimal("700"))));

        assertThat(result.totalIncome()).isEqualByComparingTo("3000.1250");
        assertThat(result.recommendedExpenseBudget()).isEqualByComparingTo("2000.0000");
        assertThat(result.totalExpenses()).isEqualByComparingTo("1900.0000");
        assertThat(result.savingCapacity()).isEqualByComparingTo("1100.1250");
        assertThat(result.deficit()).isEqualByComparingTo("0.0000");
        assertThat(result.totalIncome().scale()).isEqualTo(4);
        assertThat(result.totalExpenses().scale()).isEqualTo(4);
    }

    @Test
    void calculatesPositiveZeroAndNegativeSavingCapacity() {
        assertThat(calculateCapacity("100", "40").savingCapacity()).isEqualByComparingTo("60.0000");
        assertThat(calculateCapacity("100", "100").savingCapacity()).isEqualByComparingTo("0.0000");

        var deficit = calculateCapacity("100", "150");
        assertThat(deficit.savingCapacity()).isEqualByComparingTo("-50.0000");
        assertThat(deficit.deficit()).isEqualByComparingTo("50.0000");
    }

    @Test
    void rejectsNegativeAmountsAndMoreThanFourFractionalDigits() {
        assertThatThrownBy(() -> calculator.calculate(List.of(new BigDecimal("1.00001")), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("four fractional digits");
        assertThatThrownBy(() -> calculator.calculate(
                List.of(BigDecimal.ZERO),
                List.of(new SalaryPlanCalculator.AllocationAmount(new BigDecimal("1.00001"), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("four fractional digits");
        assertThatThrownBy(() -> calculator.calculate(List.of(new BigDecimal("-0.0001")), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> calculator.calculate(
                List.of(BigDecimal.ZERO),
                List.of(new SalaryPlanCalculator.AllocationAmount(BigDecimal.ZERO, new BigDecimal("-1")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    private SalaryPlanCalculator.Calculation calculateCapacity(String income, String expenses) {
        return calculator.calculate(
                List.of(new BigDecimal(income)),
                List.of(new SalaryPlanCalculator.AllocationAmount(new BigDecimal(expenses), null)));
    }
}
