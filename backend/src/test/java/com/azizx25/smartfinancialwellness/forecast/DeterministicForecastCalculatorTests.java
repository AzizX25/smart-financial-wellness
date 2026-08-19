package com.azizx25.smartfinancialwellness.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.azizx25.smartfinancialwellness.forecast.DeterministicForecastCalculator.CalculationRequest;
import com.azizx25.smartfinancialwellness.forecast.DeterministicForecastCalculator.FallbackReason;
import com.azizx25.smartfinancialwellness.forecast.DeterministicForecastCalculator.ForecastCalculation;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.CashFlowDirection;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.HistoricalCashFlow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicForecastCalculatorTests {

    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");
    private static final LocalDate CALCULATION_DATE = LocalDate.of(2026, 8, 20);
    private static final LocalDate CUTOFF_DATE = LocalDate.of(2026, 8, 15);

    private final DeterministicForecastCalculator calculator =
            new DeterministicForecastCalculator();

    @Test
    void averagesSeparateRemainingCreditsAndDebitsAcrossSixUsableMonths() {
        ForecastCalculation result = calculate(
                "1000.0000",
                CUTOFF_DATE,
                List.of(
                        credit("2026-02-01", "999.0000"),
                        credit("2026-03-16", "60.0000"),
                        debit("2026-03-20", "30.0000"),
                        credit("2026-04-20", "90.0000"),
                        debit("2026-04-21", "15.0000"),
                        debit("2026-05-01", "999.0000"),
                        credit("2026-06-30", "30.0000"),
                        debit("2026-06-30", "45.0000"),
                        credit("2026-07-31", "120.0000"),
                        debit("2026-07-31", "60.0000")));

        assertThat(result.expectedRemainingIncome()).isEqualByComparingTo("50.0000");
        assertThat(result.expectedRemainingExpense()).isEqualByComparingTo("25.0000");
        assertThat(result.expectedRemainingNetFlow()).isEqualByComparingTo("25.0000");
        assertThat(result.predictedEndBalance()).isEqualByComparingTo("1025.0000");
        assertThat(result.metadata().method())
                .isEqualTo(DeterministicForecastCalculator.METHOD);
        assertThat(result.metadata().methodVersion())
                .isEqualTo(DeterministicForecastCalculator.METHOD_VERSION);
        assertThat(result.metadata().timeZone()).isEqualTo("Africa/Tunis");
        assertThat(result.metadata().cutoffDate()).isEqualTo(CUTOFF_DATE);
        assertThat(result.metadata().targetMonthEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(result.metadata().historyStart()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(result.metadata().historyEnd()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(result.metadata().usableMonthCount()).isEqualTo(6);
        assertThat(result.metadata().fallbackReason()).isEqualTo(FallbackReason.NONE);
        assertThat(result.metadata().selectedAccountCount()).isEqualTo(2);
    }

    @Test
    void usesLimitedHistoryWithScaleFourHalfEvenDivision() {
        ForecastCalculation result = calculate(
                "10.0000",
                CUTOFF_DATE,
                List.of(
                        credit("2026-06-16", "1.0000"),
                        debit("2026-07-16", "2.0001")));

        assertThat(result.expectedRemainingIncome()).isEqualByComparingTo("0.5000");
        assertThat(result.expectedRemainingExpense()).isEqualByComparingTo("1.0000");
        assertThat(result.expectedRemainingNetFlow()).isEqualByComparingTo("-0.5000");
        assertThat(result.predictedEndBalance()).isEqualByComparingTo("9.5000");
        assertThat(result.metadata().usableMonthCount()).isEqualTo(2);
        assertThat(result.metadata().fallbackReason()).isEqualTo(FallbackReason.LIMITED_HISTORY);
    }

    @Test
    void carriesForwardSignedBalanceWithoutHistoryAndClampsShorterMonths() {
        ForecastCalculation noHistory = calculate("-25.2500", CUTOFF_DATE, List.of());

        assertThat(noHistory.expectedRemainingIncome()).isEqualByComparingTo("0.0000");
        assertThat(noHistory.expectedRemainingExpense()).isEqualByComparingTo("0.0000");
        assertThat(noHistory.predictedEndBalance()).isEqualByComparingTo("-25.2500");
        assertThat(noHistory.metadata().fallbackReason()).isEqualTo(FallbackReason.NO_USABLE_HISTORY);

        ForecastCalculation clamped = calculator.calculate(new CalculationRequest(
                new BigDecimal("5.0000"),
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 31),
                TUNIS,
                2,
                List.of(credit("2026-02-01", "100.0000"))));
        assertThat(clamped.expectedRemainingIncome()).isEqualByComparingTo("0.0000");
        assertThat(clamped.metadata().usableMonthCount()).isEqualTo(1);
        assertThat(clamped.metadata().fallbackReason()).isEqualTo(FallbackReason.LIMITED_HISTORY);
    }

    @Test
    void ignoresCurrentFutureAndOutOfWindowRowsAndEnforcesCurrentMonthCutoff() {
        ForecastCalculation result = calculate(
                "0.0000",
                CUTOFF_DATE,
                List.of(
                        credit("2026-01-31", "999.0000"),
                        credit("2026-07-16", "3.0000"),
                        credit("2026-08-16", "999.0000"),
                        debit("2026-09-01", "999.0000")));

        assertThat(result.expectedRemainingIncome()).isEqualByComparingTo("3.0000");
        assertThat(result.expectedRemainingExpense()).isEqualByComparingTo("0.0000");
        assertThat(result.metadata().usableMonthCount()).isEqualTo(1);

        assertThatThrownBy(() -> calculate(
                "0.0000", LocalDate.of(2026, 7, 31), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(new CalculationRequest(
                new BigDecimal("0.0000"),
                CALCULATION_DATE,
                LocalDate.of(2026, 8, 21),
                TUNIS,
                2,
                List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ForecastCalculation calculate(
            String startingBalance,
            LocalDate cutoffDate,
            List<HistoricalCashFlow> history) {
        return calculator.calculate(new CalculationRequest(
                new BigDecimal(startingBalance),
                CALCULATION_DATE,
                cutoffDate,
                TUNIS,
                2,
                history));
    }

    private HistoricalCashFlow credit(String date, String amount) {
        return cashFlow(date, amount, CashFlowDirection.CREDIT);
    }

    private HistoricalCashFlow debit(String date, String amount) {
        return cashFlow(date, amount, CashFlowDirection.DEBIT);
    }

    private HistoricalCashFlow cashFlow(
            String date,
            String amount,
            CashFlowDirection direction) {
        return new HistoricalCashFlow(LocalDate.parse(date), new BigDecimal(amount), direction);
    }
}
