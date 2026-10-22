package com.azizx25.smartfinancialwellness.salary;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class SalaryPlanCalculator {

    public Calculation calculate(
            List<BigDecimal> expectedIncomeAmounts,
            List<AllocationAmount> allocations) {
        Objects.requireNonNull(expectedIncomeAmounts, "expectedIncomeAmounts is required");
        Objects.requireNonNull(allocations, "allocations is required");

        BigDecimal totalIncome = expectedIncomeAmounts.stream()
                .map(amount -> MonetaryAmounts.requireNonNegativeInput(amount, "expected income"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recommendedExpenses = allocations.stream()
                .map(AllocationAmount::validatedRecommendedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExpenses = allocations.stream()
                .map(AllocationAmount::effectiveAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal savingCapacity = totalIncome.subtract(totalExpenses);
        BigDecimal deficit = savingCapacity.signum() < 0 ? savingCapacity.negate() : BigDecimal.ZERO;

        return new Calculation(
                MonetaryAmounts.calculated(totalIncome),
                MonetaryAmounts.calculated(recommendedExpenses),
                MonetaryAmounts.calculated(totalExpenses),
                MonetaryAmounts.calculated(savingCapacity),
                MonetaryAmounts.calculated(deficit));
    }

    public record AllocationAmount(BigDecimal recommendedAmount, BigDecimal confirmedAmount) {

        private BigDecimal validatedRecommendedAmount() {
            return MonetaryAmounts.requireNonNegativeInput(recommendedAmount, "recommended allocation");
        }

        private BigDecimal effectiveAmount() {
            if (confirmedAmount == null) {
                return validatedRecommendedAmount();
            }
            return MonetaryAmounts.requireNonNegativeInput(confirmedAmount, "confirmed allocation");
        }
    }

    public record Calculation(
            BigDecimal totalIncome,
            BigDecimal recommendedExpenseBudget,
            BigDecimal totalExpenses,
            BigDecimal savingCapacity,
            BigDecimal deficit) {
    }
}
