package com.azizx25.smartfinancialwellness.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class FinancialWellnessScoreCalculator {

    public static final String VERSION = "financial-wellness-score-v1";
    public static final int SCALE = 4;
    private static final int DIVISION_SCALE = 12;
    private static final BigDecimal ZERO = new BigDecimal("0.0000");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public Calculation calculate(Input input) {
        Objects.requireNonNull(input, "score input is required");
        List<MonthlyCashFlow> usableMonths = input.monthlyCashFlows().stream()
                .filter(month -> month.income().signum() > 0)
                .toList();

        List<FactorResult> factors = new ArrayList<>();
        factors.add(savingsRate(usableMonths));
        factors.add(essentialBurden(input.totalEssentialExpense(), totalIncome(usableMonths)));
        factors.add(incomeStability(usableMonths));
        factors.add(balanceResilience(
                input.latestAggregateLedgerBalance(), input.averageMonthlyEssentialExpense()));
        factors.add(salaryPlanAdherence(input.salaryPlanAdherenceRatios()));
        factors.add(goalProgress(input.goalProgressScores()));

        int availableWeight = factors.stream()
                .filter(FactorResult::available)
                .mapToInt(factor -> factor.code().baseWeight())
                .sum();
        boolean savingsAvailable = factors.getFirst().available();
        if (!savingsAvailable || availableWeight < 50) {
            return new Calculation(false, "INSUFFICIENT_DATA", null, null,
                    availableWeight, List.copyOf(factors));
        }

        BigDecimal weighted = factors.stream()
                .filter(FactorResult::available)
                .map(factor -> factor.score().multiply(BigDecimal.valueOf(factor.code().baseWeight())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = divide(weighted, BigDecimal.valueOf(availableWeight))
                .setScale(SCALE, RoundingMode.HALF_EVEN);
        Band band = Band.from(total);
        return new Calculation(true, "AVAILABLE", total, band, availableWeight, List.copyOf(factors));
    }

    private FactorResult savingsRate(List<MonthlyCashFlow> months) {
        if (months.size() < 3) {
            return FactorResult.unavailable(FactorCode.SAVINGS_RATE, "RATIO",
                    "Au moins trois mois terminés avec des revenus positifs sont nécessaires.",
                    "INSUFFICIENT_POSITIVE_INCOME_MONTHS");
        }
        BigDecimal income = totalIncome(months);
        BigDecimal expense = months.stream().map(MonthlyCashFlow::expense)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rate = divide(income.subtract(expense), income);
        BigDecimal score = clamp(rate.multiply(new BigDecimal("500")));
        return FactorResult.available(FactorCode.SAVINGS_RATE, rate, "RATIO", score,
                "Le taux d'épargne est calculé sur " + months.size()
                        + " mois terminés avec des revenus positifs.");
    }

    private FactorResult essentialBurden(Optional<BigDecimal> expense, BigDecimal income) {
        if (expense.isEmpty()) {
            return FactorResult.unavailable(FactorCode.ESSENTIAL_EXPENSE_BURDEN, "RATIO",
                    "Les catégories actuelles ne distinguent pas les dépenses essentielles.",
                    "ESSENTIAL_CLASSIFICATION_UNAVAILABLE");
        }
        if (income.signum() <= 0) {
            return FactorResult.unavailable(FactorCode.ESSENTIAL_EXPENSE_BURDEN, "RATIO",
                    "Aucun revenu positif utilisable n'est disponible.", "NO_POSITIVE_INCOME");
        }
        BigDecimal ratio = divide(expense.orElseThrow(), income);
        BigDecimal score = clamp(ONE.subtract(ratio.subtract(new BigDecimal("0.5000"))
                .divide(new BigDecimal("0.4000"), DIVISION_SCALE, RoundingMode.HALF_EVEN))
                .multiply(ONE_HUNDRED));
        return FactorResult.available(FactorCode.ESSENTIAL_EXPENSE_BURDEN, ratio, "RATIO", score,
                "La part des dépenses essentielles est comparée aux revenus éligibles.");
    }

    private FactorResult incomeStability(List<MonthlyCashFlow> months) {
        if (months.size() < 3) {
            return FactorResult.unavailable(FactorCode.INCOME_STABILITY, "RATIO",
                    "Au moins trois mois de revenus positifs sont nécessaires.",
                    "INSUFFICIENT_POSITIVE_INCOME_MONTHS");
        }
        BigDecimal mean = divide(totalIncome(months), BigDecimal.valueOf(months.size()));
        BigDecimal deviation = months.stream()
                .map(month -> month.income().subtract(mean).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal normalized = divide(divide(deviation, BigDecimal.valueOf(months.size())), mean);
        BigDecimal score = clamp(ONE.subtract(normalized.divide(
                new BigDecimal("0.5000"), DIVISION_SCALE, RoundingMode.HALF_EVEN))
                .multiply(ONE_HUNDRED));
        return FactorResult.available(FactorCode.INCOME_STABILITY, normalized, "RATIO", score,
                "La variation moyenne des revenus est mesurée sur les mois terminés utilisables.");
    }

    private FactorResult balanceResilience(
            Optional<BigDecimal> ledgerBalance, Optional<BigDecimal> averageEssentialExpense) {
        if (ledgerBalance.isEmpty() || averageEssentialExpense.isEmpty()
                || averageEssentialExpense.orElseThrow().signum() <= 0) {
            return FactorResult.unavailable(FactorCode.BALANCE_RESILIENCE, "MONTHS",
                    "Un solde commun fiable et des dépenses essentielles sont nécessaires.",
                    "RELIABLE_BUFFER_INPUT_UNAVAILABLE");
        }
        BigDecimal months = divide(ledgerBalance.orElseThrow(), averageEssentialExpense.orElseThrow());
        BigDecimal score = clamp(months.divide(
                new BigDecimal("3.0000"), DIVISION_SCALE, RoundingMode.HALF_EVEN)
                .multiply(ONE_HUNDRED));
        return FactorResult.available(FactorCode.BALANCE_RESILIENCE, months, "MONTHS", score,
                "Le solde comptable agrégé couvre ce nombre de mois de dépenses essentielles moyennes.");
    }

    private FactorResult salaryPlanAdherence(List<BigDecimal> ratios) {
        if (ratios.isEmpty()) {
            return FactorResult.unavailable(FactorCode.SALARY_PLAN_ADHERENCE, "RATIO",
                    "Aucun budget mensuel terminé et utilisable n'est disponible.",
                    "NO_COMPLETED_SALARY_PLAN");
        }
        BigDecimal average = divide(
                ratios.stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                BigDecimal.valueOf(ratios.size()));
        BigDecimal score = clamp(ONE.subtract(average.subtract(ONE)
                .divide(new BigDecimal("0.5000"), DIVISION_SCALE, RoundingMode.HALF_EVEN))
                .multiply(ONE_HUNDRED));
        return FactorResult.available(FactorCode.SALARY_PLAN_ADHERENCE, average, "RATIO", score,
                "Les dépenses réelles sont comparées aux budgets prévus des mois terminés.");
    }

    private FactorResult goalProgress(List<BigDecimal> scores) {
        if (scores.isEmpty()) {
            return FactorResult.unavailable(FactorCode.GOAL_PROGRESS, "PERCENT",
                    "Aucun objectif actif ou atteint avec une échéance utilisable n'est disponible.",
                    "NO_USABLE_GOAL");
        }
        BigDecimal average = divide(scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                BigDecimal.valueOf(scores.size()));
        return FactorResult.available(FactorCode.GOAL_PROGRESS, average, "PERCENT", clamp(average),
                "La progression réelle est comparée au temps écoulé pour les objectifs utilisables.");
    }

    private BigDecimal totalIncome(List<MonthlyCashFlow> months) {
        return months.stream().map(MonthlyCashFlow::income).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            throw new IllegalArgumentException("division by zero");
        }
        return numerator.divide(denominator, DIVISION_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(ONE_HUNDRED).setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal value(BigDecimal input, String name) {
        Objects.requireNonNull(input, name + " is required");
        if (input.scale() > SCALE) {
            throw new IllegalArgumentException(name + " must have at most four fractional digits");
        }
        return input.setScale(SCALE);
    }

    public enum FactorCode {
        SAVINGS_RATE(30),
        ESSENTIAL_EXPENSE_BURDEN(20),
        INCOME_STABILITY(15),
        BALANCE_RESILIENCE(15),
        SALARY_PLAN_ADHERENCE(10),
        GOAL_PROGRESS(10);

        private final int baseWeight;

        FactorCode(int baseWeight) { this.baseWeight = baseWeight; }
        public int baseWeight() { return baseWeight; }
    }

    public enum Band {
        A_RENFORCER("À renforcer"),
        FRAGILE("Fragile"),
        EN_PROGRESSION("En progression"),
        SOLIDE("Solide");

        private final String frenchLabel;

        Band(String frenchLabel) { this.frenchLabel = frenchLabel; }
        public String frenchLabel() { return frenchLabel; }

        static Band from(BigDecimal score) {
            if (score.compareTo(new BigDecimal("40.0000")) < 0) return A_RENFORCER;
            if (score.compareTo(new BigDecimal("60.0000")) < 0) return FRAGILE;
            if (score.compareTo(new BigDecimal("80.0000")) < 0) return EN_PROGRESSION;
            return SOLIDE;
        }
    }

    public record MonthlyCashFlow(YearMonth month, BigDecimal income, BigDecimal expense) {
        public MonthlyCashFlow {
            Objects.requireNonNull(month, "month is required");
            income = value(income, "income");
            expense = value(expense, "expense");
            if (income.signum() < 0 || expense.signum() < 0) {
                throw new IllegalArgumentException("monthly cash flow values must be non-negative");
            }
        }
    }

    public record Input(
            List<MonthlyCashFlow> monthlyCashFlows,
            Optional<BigDecimal> totalEssentialExpense,
            Optional<BigDecimal> averageMonthlyEssentialExpense,
            Optional<BigDecimal> latestAggregateLedgerBalance,
            List<BigDecimal> salaryPlanAdherenceRatios,
            List<BigDecimal> goalProgressScores) {
        public Input {
            monthlyCashFlows = List.copyOf(monthlyCashFlows);
            totalEssentialExpense = totalEssentialExpense == null ? Optional.empty() : totalEssentialExpense;
            averageMonthlyEssentialExpense = averageMonthlyEssentialExpense == null
                    ? Optional.empty() : averageMonthlyEssentialExpense;
            latestAggregateLedgerBalance = latestAggregateLedgerBalance == null
                    ? Optional.empty() : latestAggregateLedgerBalance;
            salaryPlanAdherenceRatios = salaryPlanAdherenceRatios.stream()
                    .map(value -> value(value, "salary adherence ratio")).toList();
            goalProgressScores = goalProgressScores.stream()
                    .map(value -> value(value, "goal progress score")).toList();
        }
    }

    public record FactorResult(
            FactorCode code, BigDecimal rawValue, String unit, BigDecimal score,
            boolean available, String explanation, String unavailabilityReason) {
        static FactorResult available(
                FactorCode code, BigDecimal rawValue, String unit,
                BigDecimal score, String explanation) {
            return new FactorResult(code, rawValue.setScale(SCALE, RoundingMode.HALF_EVEN), unit,
                    score.setScale(SCALE, RoundingMode.HALF_EVEN), true, explanation, null);
        }

        static FactorResult unavailable(
                FactorCode code, String unit, String explanation, String reason) {
            return new FactorResult(code, null, unit, null, false, explanation, reason);
        }
    }

    public record Calculation(
            boolean available, String status, BigDecimal score, Band band,
            int availableBaseWeight, List<FactorResult> factors) {
    }
}
