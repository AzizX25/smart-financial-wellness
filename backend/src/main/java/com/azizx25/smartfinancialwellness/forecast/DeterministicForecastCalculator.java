package com.azizx25.smartfinancialwellness.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.CashFlowDirection;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.HistoricalCashFlow;
import org.springframework.stereotype.Component;

@Component
public class DeterministicForecastCalculator {

    public static final String METHOD = "HISTORICAL_REMAINING_PERIOD_AVERAGE";
    public static final String METHOD_VERSION = "1";

    private static final int MONEY_SCALE = 4;
    private static final int HISTORY_MONTHS = 6;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE);

    public ForecastCalculation calculate(CalculationRequest request) {
        validate(request);
        YearMonth targetMonth = YearMonth.from(request.calculationDate());
        YearMonth firstHistoryMonth = targetMonth.minusMonths(HISTORY_MONTHS);
        BigDecimal totalRemainingIncome = ZERO;
        BigDecimal totalRemainingExpense = ZERO;
        int usableMonthCount = 0;

        for (int offset = 0; offset < HISTORY_MONTHS; offset++) {
            YearMonth historyMonth = firstHistoryMonth.plusMonths(offset);
            List<HistoricalCashFlow> monthCashFlows = request.historicalCashFlows().stream()
                    .filter(cashFlow -> YearMonth.from(cashFlow.bookingDate()).equals(historyMonth))
                    .toList();
            if (monthCashFlows.isEmpty()) {
                continue;
            }

            usableMonthCount++;
            LocalDate analogousCutoff = historyMonth.atDay(
                    Math.min(request.cutoffDate().getDayOfMonth(), historyMonth.lengthOfMonth()));
            for (HistoricalCashFlow cashFlow : monthCashFlows) {
                if (!cashFlow.bookingDate().isAfter(analogousCutoff)) {
                    continue;
                }
                if (cashFlow.direction() == CashFlowDirection.CREDIT) {
                    totalRemainingIncome = totalRemainingIncome.add(cashFlow.amount());
                } else {
                    totalRemainingExpense = totalRemainingExpense.add(cashFlow.amount());
                }
            }
        }

        BigDecimal expectedIncome = average(totalRemainingIncome, usableMonthCount);
        BigDecimal expectedExpense = average(totalRemainingExpense, usableMonthCount);
        BigDecimal expectedNet = expectedIncome.subtract(expectedExpense).setScale(MONEY_SCALE);
        BigDecimal startingBalance = request.startingBalance().setScale(
                MONEY_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal predictedEndBalance = startingBalance.add(expectedNet).setScale(MONEY_SCALE);
        FallbackReason fallbackReason = switch (usableMonthCount) {
            case 0 -> FallbackReason.NO_USABLE_HISTORY;
            case 1, 2 -> FallbackReason.LIMITED_HISTORY;
            default -> FallbackReason.NONE;
        };

        return new ForecastCalculation(
                expectedIncome,
                expectedExpense,
                expectedNet,
                predictedEndBalance,
                new CalculationMetadata(
                        METHOD,
                        METHOD_VERSION,
                        request.timeZone().getId(),
                        request.cutoffDate(),
                        targetMonth.atEndOfMonth(),
                        firstHistoryMonth.atDay(1),
                        targetMonth.minusMonths(1).atEndOfMonth(),
                        usableMonthCount,
                        fallbackReason,
                        request.selectedAccountCount()));
    }

    private BigDecimal average(BigDecimal total, int divisor) {
        if (divisor == 0) {
            return ZERO;
        }
        return total.divide(BigDecimal.valueOf(divisor), MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    private void validate(CalculationRequest request) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(request.startingBalance(), "startingBalance is required");
        Objects.requireNonNull(request.calculationDate(), "calculationDate is required");
        Objects.requireNonNull(request.cutoffDate(), "cutoffDate is required");
        Objects.requireNonNull(request.timeZone(), "timeZone is required");
        Objects.requireNonNull(request.historicalCashFlows(), "historicalCashFlows is required");
        request.startingBalance().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        if (request.selectedAccountCount() < 1) {
            throw new IllegalArgumentException("selectedAccountCount must be positive");
        }
        if (!YearMonth.from(request.cutoffDate()).equals(YearMonth.from(request.calculationDate()))
                || request.cutoffDate().isAfter(request.calculationDate())) {
            throw new IllegalArgumentException(
                    "cutoffDate must be within the current calculation month and not in the future");
        }
    }

    public enum FallbackReason {
        NONE, LIMITED_HISTORY, NO_USABLE_HISTORY
    }

    public record CalculationRequest(
            BigDecimal startingBalance,
            LocalDate calculationDate,
            LocalDate cutoffDate,
            ZoneId timeZone,
            int selectedAccountCount,
            List<HistoricalCashFlow> historicalCashFlows) {

        public CalculationRequest {
            historicalCashFlows = historicalCashFlows == null
                    ? null
                    : List.copyOf(historicalCashFlows);
        }
    }

    public record ForecastCalculation(
            BigDecimal expectedRemainingIncome,
            BigDecimal expectedRemainingExpense,
            BigDecimal expectedRemainingNetFlow,
            BigDecimal predictedEndBalance,
            CalculationMetadata metadata) {
    }

    public record CalculationMetadata(
            String method,
            String methodVersion,
            String timeZone,
            LocalDate cutoffDate,
            LocalDate targetMonthEnd,
            LocalDate historyStart,
            LocalDate historyEnd,
            int usableMonthCount,
            FallbackReason fallbackReason,
            int selectedAccountCount) {
    }
}
