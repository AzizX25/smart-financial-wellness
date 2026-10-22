package com.azizx25.smartfinancialwellness.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.azizx25.smartfinancialwellness.forecast.DeterministicForecastCalculator.ForecastCalculation;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.CashFlowDirection;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.ForecastInput;
import com.azizx25.smartfinancialwellness.forecast.ForecastInputExtractor.HistoricalCashFlow;
import org.springframework.stereotype.Component;

@Component
public class ForecastFeatureAssembler {

    public static final String FEATURE_VERSION = "forecast-residual-features-v1";
    private static final int SCALE = 4;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    public ForecastFeatures assemble(ForecastInput input, ForecastCalculation calculation) {
        YearMonth month = YearMonth.from(input.cutoffDate());
        Totals current = totals(input.currentMonthCashFlows());
        Totals trailing = totals(input.historicalCashFlows());
        List<BigDecimal> remainingNets = new ArrayList<>();
        for (int offset = 6; offset >= 1; offset--) {
            YearMonth historicalMonth = month.minusMonths(offset);
            List<HistoricalCashFlow> monthFlows = input.historicalCashFlows().stream()
                    .filter(flow -> YearMonth.from(flow.bookingDate()).equals(historicalMonth))
                    .toList();
            if (monthFlows.isEmpty()) {
                continue;
            }
            LocalDate analogousCutoff = historicalMonth.atDay(
                    Math.min(input.cutoffDate().getDayOfMonth(), historicalMonth.lengthOfMonth()));
            remainingNets.add(totals(monthFlows.stream()
                    .filter(flow -> flow.bookingDate().isAfter(analogousCutoff))
                    .toList()).net());
        }
        BigDecimal range = remainingNets.isEmpty()
                ? ZERO
                : remainingNets.stream().max(Comparator.naturalOrder()).orElseThrow()
                        .subtract(remainingNets.stream().min(Comparator.naturalOrder()).orElseThrow())
                        .setScale(SCALE, RoundingMode.UNNECESSARY);
        return new ForecastFeatures(
                FEATURE_VERSION, month.toString(), input.cutoffDate().getDayOfMonth(),
                month.atEndOfMonth().getDayOfMonth() - input.cutoffDate().getDayOfMonth(),
                current.credit(), current.debit(), current.net(), input.currentMonthCashFlows().size(),
                trailing.credit(), trailing.debit(), trailing.net(), range,
                calculation.expectedRemainingIncome(), calculation.expectedRemainingExpense(),
                calculation.expectedRemainingNetFlow(), calculation.metadata().usableMonthCount(),
                calculation.metadata().fallbackReason().name());
    }

    private Totals totals(List<HistoricalCashFlow> flows) {
        BigDecimal credit = ZERO;
        BigDecimal debit = ZERO;
        for (HistoricalCashFlow flow : flows) {
            if (flow.direction() == CashFlowDirection.CREDIT) {
                credit = credit.add(flow.amount());
            } else {
                debit = debit.add(flow.amount());
            }
        }
        return new Totals(credit.setScale(SCALE), debit.setScale(SCALE),
                credit.subtract(debit).setScale(SCALE));
    }

    private record Totals(BigDecimal credit, BigDecimal debit, BigDecimal net) {
    }

    public record ForecastFeatures(
            String featureVersion, String calendarMonth, int cutoffDay, int daysRemaining,
            BigDecimal monthToDateCredit, BigDecimal monthToDateDebit,
            BigDecimal monthToDateNetFlow, int monthToDateTransactionCount,
            BigDecimal trailingSixMonthCredit, BigDecimal trailingSixMonthDebit,
            BigDecimal trailingSixMonthNetFlow, BigDecimal historicalRemainingNetFlowRange,
            BigDecimal deterministicRemainingIncome, BigDecimal deterministicRemainingExpense,
            BigDecimal deterministicRemainingNetFlow, int usableHistoryCount,
            String deterministicFallbackReason) {
    }
}
