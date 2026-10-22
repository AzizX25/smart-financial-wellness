package com.azizx25.smartfinancialwellness.score;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.Band;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.Calculation;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.FactorCode;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.FactorResult;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.Input;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.MonthlyCashFlow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialWellnessScoreCalculatorTests {

    private final FinancialWellnessScoreCalculator calculator = new FinancialWellnessScoreCalculator();

    @Test
    void savingsRateUsesExactBoundsMidpointAndClampsNegativeValues() {
        assertThat(factor(calculate("100.0000", "100.0000"), FactorCode.SAVINGS_RATE).score())
                .isEqualByComparingTo("0.0000");
        assertThat(factor(calculate("100.0000", "90.0000"), FactorCode.SAVINGS_RATE).score())
                .isEqualByComparingTo("50.0000");
        assertThat(factor(calculate("100.0000", "80.0000"), FactorCode.SAVINGS_RATE).score())
                .isEqualByComparingTo("100.0000");
        assertThat(factor(calculate("100.0000", "120.0000"), FactorCode.SAVINGS_RATE).score())
                .isEqualByComparingTo("0.0000");
    }

    @Test
    void essentialBurdenBalanceSalaryAndGoalFactorsUseTheirExactLinearThresholds() {
        Calculation result = calculator.calculate(new Input(
                flows("100.0000", "50.0000"), Optional.of(new BigDecimal("210.0000")),
                Optional.of(new BigDecimal("100.0000")), Optional.of(new BigDecimal("150.0000")),
                List.of(new BigDecimal("1.2500")), List.of(new BigDecimal("50.0000"))));

        assertThat(factor(result, FactorCode.ESSENTIAL_EXPENSE_BURDEN).rawValue())
                .isEqualByComparingTo("0.7000");
        assertThat(factor(result, FactorCode.ESSENTIAL_EXPENSE_BURDEN).score())
                .isEqualByComparingTo("50.0000");
        assertThat(factor(result, FactorCode.BALANCE_RESILIENCE).score())
                .isEqualByComparingTo("50.0000");
        assertThat(factor(result, FactorCode.SALARY_PLAN_ADHERENCE).score())
                .isEqualByComparingTo("50.0000");
        assertThat(factor(result, FactorCode.GOAL_PROGRESS).score())
                .isEqualByComparingTo("50.0000");

        Calculation bounds = calculator.calculate(new Input(
                flows("100.0000", "50.0000"), Optional.of(new BigDecimal("270.0000")),
                Optional.of(new BigDecimal("100.0000")), Optional.of(BigDecimal.ZERO.setScale(4)),
                List.of(new BigDecimal("1.5000")), List.of(BigDecimal.ZERO.setScale(4))));
        assertThat(factor(bounds, FactorCode.ESSENTIAL_EXPENSE_BURDEN).score())
                .isEqualByComparingTo("0.0000");
        assertThat(factor(bounds, FactorCode.BALANCE_RESILIENCE).score())
                .isEqualByComparingTo("0.0000");
        assertThat(factor(bounds, FactorCode.SALARY_PLAN_ADHERENCE).score())
                .isEqualByComparingTo("0.0000");
        assertThat(factor(bounds, FactorCode.GOAL_PROGRESS).score())
                .isEqualByComparingTo("0.0000");
    }

    @Test
    void incomeStabilityUsesMeanAbsoluteDeviationAndIsMonotonic() {
        Calculation stable = calculator.calculate(inputWithIncomes("100.0000", "100.0000", "100.0000"));
        Calculation midpoint = calculator.calculate(inputWithIncomes("62.5000", "100.0000", "137.5000"));
        Calculation variable = calculator.calculate(inputWithIncomes("25.0000", "100.0000", "175.0000"));

        assertThat(factor(stable, FactorCode.INCOME_STABILITY).score()).isEqualByComparingTo("100.0000");
        assertThat(factor(midpoint, FactorCode.INCOME_STABILITY).score()).isEqualByComparingTo("50.0000");
        assertThat(factor(variable, FactorCode.INCOME_STABILITY).score()).isEqualByComparingTo("0.0000");
        assertThat(factor(stable, FactorCode.INCOME_STABILITY).score())
                .isGreaterThan(factor(midpoint, FactorCode.INCOME_STABILITY).score());
    }

    @Test
    void missingFactorsAreRenormalizedButMinimumCoverageAndSavingsHistoryRemainMandatory() {
        Calculation enoughWeight = calculator.calculate(new Input(
                flows("100.0000", "80.0000"), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new BigDecimal("1.0000")), List.of()));
        assertThat(enoughWeight.available()).isTrue();
        assertThat(enoughWeight.availableBaseWeight()).isEqualTo(55);
        assertThat(enoughWeight.score()).isEqualByComparingTo("100.0000");

        Calculation onlyHistory = calculator.calculate(new Input(
                flows("100.0000", "80.0000"), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), List.of()));
        assertThat(onlyHistory.available()).isFalse();
        assertThat(onlyHistory.availableBaseWeight()).isEqualTo(45);
        assertThat(onlyHistory.score()).isNull();

        Calculation tooShort = calculator.calculate(new Input(
                flows(2, "100.0000", "80.0000"), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new BigDecimal("1.0000")), List.of(new BigDecimal("100.0000"))));
        assertThat(tooShort.available()).isFalse();
        assertThat(factor(tooShort, FactorCode.SAVINGS_RATE).unavailabilityReason())
                .isEqualTo("INSUFFICIENT_POSITIVE_INCOME_MONTHS");
    }

    @Test
    void outputsAreFiniteScaleFourAndBandBoundariesAreStable() {
        Calculation repeatingDivision = calculator.calculate(new Input(
                flows("3.0000", "2.0000"), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new BigDecimal("1.3333")), List.of(new BigDecimal("33.3333"))));
        repeatingDivision.factors().stream().filter(FactorResult::available).forEach(factor -> {
            assertThat(factor.score().scale()).isEqualTo(4);
            assertThat(factor.rawValue().scale()).isEqualTo(4);
            assertThat(factor.score().toPlainString()).doesNotContain("NaN", "Infinity");
        });
        assertThat(repeatingDivision.score().scale()).isEqualTo(4);

        assertThat(Band.from(new BigDecimal("0.0000"))).isEqualTo(Band.A_RENFORCER);
        assertThat(Band.from(new BigDecimal("39.9999"))).isEqualTo(Band.A_RENFORCER);
        assertThat(Band.from(new BigDecimal("40.0000"))).isEqualTo(Band.FRAGILE);
        assertThat(Band.from(new BigDecimal("59.9999"))).isEqualTo(Band.FRAGILE);
        assertThat(Band.from(new BigDecimal("60.0000"))).isEqualTo(Band.EN_PROGRESSION);
        assertThat(Band.from(new BigDecimal("79.9999"))).isEqualTo(Band.EN_PROGRESSION);
        assertThat(Band.from(new BigDecimal("80.0000"))).isEqualTo(Band.SOLIDE);
        assertThat(Band.from(new BigDecimal("100.0000"))).isEqualTo(Band.SOLIDE);
    }

    private Calculation calculate(String income, String expense) {
        return calculator.calculate(new Input(
                flows(income, expense), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new BigDecimal("1.0000")), List.of(new BigDecimal("100.0000"))));
    }

    private Input inputWithIncomes(String first, String second, String third) {
        return new Input(List.of(
                flow(0, first, "0.0000"), flow(1, second, "0.0000"),
                flow(2, third, "0.0000")), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new BigDecimal("1.0000")), List.of());
    }

    private List<MonthlyCashFlow> flows(String income, String expense) {
        return flows(3, income, expense);
    }

    private List<MonthlyCashFlow> flows(int count, String income, String expense) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> flow(index, income, expense)).toList();
    }

    private MonthlyCashFlow flow(int offset, String income, String expense) {
        return new MonthlyCashFlow(YearMonth.of(2026, 1).plusMonths(offset),
                new BigDecimal(income), new BigDecimal(expense));
    }

    private FactorResult factor(Calculation result, FactorCode code) {
        return result.factors().stream().filter(item -> item.code() == code).findFirst().orElseThrow();
    }
}
