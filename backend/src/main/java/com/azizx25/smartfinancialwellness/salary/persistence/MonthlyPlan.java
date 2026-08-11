package com.azizx25.smartfinancialwellness.salary.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.salary.MonetaryAmounts;
import com.azizx25.smartfinancialwellness.salary.SalaryPlanCalculator.Calculation;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "monthly_plan", schema = "smart_financial_wellness")
public class MonthlyPlan {

    public enum Status { DRAFT, ACTIVE, CLOSED, ARCHIVED }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "period_month", nullable = false)
    private java.time.LocalDate periodMonth;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "expected_income_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedIncomeTotal;

    @Column(name = "recommended_expense_budget", nullable = false, precision = 19, scale = 4)
    private BigDecimal recommendedExpenseBudget;

    @Column(name = "calculated_saving_capacity", nullable = false, precision = 19, scale = 4)
    private BigDecimal calculatedSavingCapacity;

    @Column(name = "calculation_method", nullable = false, length = 80)
    private String calculationMethod;

    @Column(name = "method_version", nullable = false, length = 60)
    private String methodVersion;

    @Column(name = "recommendation_metadata_available", nullable = false)
    private boolean recommendationMetadataAvailable;

    @Column(name = "recommendation_period_start")
    private LocalDate recommendationPeriodStart;

    @Column(name = "recommendation_period_end")
    private LocalDate recommendationPeriodEnd;

    @Column(name = "recommendation_usable_month_count")
    private Integer recommendationUsableMonthCount;

    @Column(name = "recommendation_fallback_reason", length = 60)
    private String recommendationFallbackReason;

    @Column(name = "calculated_at")
    private Instant calculatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    @OneToMany(mappedBy = "monthlyPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<PlannedIncome> plannedIncomes = new ArrayList<>();

    @OneToMany(mappedBy = "monthlyPlan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<BudgetAllocation> budgetAllocations = new ArrayList<>();

    protected MonthlyPlan() {
    }

    public MonthlyPlan(AppUser owner, YearMonth month, String planCurrency, Instant now) {
        id = UUID.randomUUID();
        user = Objects.requireNonNull(owner, "owner is required");
        periodMonth = Objects.requireNonNull(month, "month is required").atDay(1);
        currency = MonetaryAmounts.requireCurrency(planCurrency);
        status = Status.DRAFT;
        expectedIncomeTotal = BigDecimal.ZERO.setScale(MonetaryAmounts.SCALE);
        recommendedExpenseBudget = BigDecimal.ZERO.setScale(MonetaryAmounts.SCALE);
        calculatedSavingCapacity = BigDecimal.ZERO.setScale(MonetaryAmounts.SCALE);
        calculationMethod = "DETERMINISTIC";
        methodVersion = "1";
        recommendationMetadataAvailable = false;
        createdAt = Objects.requireNonNull(now, "now is required");
        updatedAt = now;
    }

    public PlannedIncome addIncome(
            String label,
            PlannedIncome.IncomeType type,
            BigDecimal amount,
            java.time.LocalDate expectedDate,
            boolean recurring,
            Instant now) {
        PlannedIncome income = new PlannedIncome(this, label, type, amount, expectedDate, recurring, now);
        plannedIncomes.add(income);
        return income;
    }

    public void clearIncomes() {
        plannedIncomes.clear();
    }

    public BudgetAllocation addAllocation(
            Category category,
            BigDecimal recommendedAmount,
            BudgetAllocation.RecommendationOrigin origin,
            Instant now) {
        BudgetAllocation allocation = new BudgetAllocation(this, category, recommendedAmount, origin, now);
        budgetAllocations.add(allocation);
        return allocation;
    }

    public void retainAllocationsFor(Set<UUID> categoryIds) {
        Objects.requireNonNull(categoryIds, "categoryIds is required");
        budgetAllocations.removeIf(allocation -> !categoryIds.contains(allocation.getCategory().getId()));
    }

    public void applyCalculation(Calculation calculation, String method, String calculationVersion, Instant now) {
        applyCalculationValues(calculation, method, calculationVersion, now);
        recommendationMetadataAvailable = false;
        recommendationPeriodStart = null;
        recommendationPeriodEnd = null;
        recommendationUsableMonthCount = null;
        recommendationFallbackReason = null;
    }

    public void applyRecommendationCalculation(
            Calculation calculation,
            RecommendationSnapshot recommendation,
            Instant now) {
        Objects.requireNonNull(recommendation, "recommendation is required");
        applyCalculationValues(calculation, recommendation.method(), recommendation.methodVersion(), now);
        recommendationMetadataAvailable = true;
        recommendationPeriodStart = recommendation.periodStart();
        recommendationPeriodEnd = recommendation.periodEndInclusive();
        recommendationUsableMonthCount = recommendation.usableMonthCount();
        recommendationFallbackReason = recommendation.fallbackReason();
    }

    private void applyCalculationValues(
            Calculation calculation,
            String method,
            String calculationVersion,
            Instant now) {
        Objects.requireNonNull(calculation, "calculation is required");
        if (method == null || method.isBlank() || calculationVersion == null || calculationVersion.isBlank()) {
            throw new IllegalArgumentException("calculation method and version are required");
        }
        expectedIncomeTotal = calculation.totalIncome();
        recommendedExpenseBudget = calculation.recommendedExpenseBudget();
        calculatedSavingCapacity = calculation.savingCapacity();
        calculationMethod = method;
        methodVersion = calculationVersion;
        calculatedAt = Objects.requireNonNull(now, "now is required");
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return user.getId(); }
    public YearMonth getPeriodMonth() { return YearMonth.from(periodMonth); }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public BigDecimal getExpectedIncomeTotal() { return expectedIncomeTotal; }
    public BigDecimal getRecommendedExpenseBudget() { return recommendedExpenseBudget; }
    public BigDecimal getCalculatedSavingCapacity() { return calculatedSavingCapacity; }
    public String getCalculationMethod() { return calculationMethod; }
    public String getMethodVersion() { return methodVersion; }
    public boolean isRecommendationMetadataAvailable() { return recommendationMetadataAvailable; }
    public LocalDate getRecommendationPeriodStart() { return recommendationPeriodStart; }
    public LocalDate getRecommendationPeriodEnd() { return recommendationPeriodEnd; }
    public Integer getRecommendationUsableMonthCount() { return recommendationUsableMonthCount; }
    public String getRecommendationFallbackReason() { return recommendationFallbackReason; }
    public Instant getCalculatedAt() { return calculatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
    public List<PlannedIncome> getPlannedIncomes() { return List.copyOf(plannedIncomes); }
    public List<BudgetAllocation> getBudgetAllocations() { return List.copyOf(budgetAllocations); }

    public record RecommendationSnapshot(
            String method,
            String methodVersion,
            LocalDate periodStart,
            LocalDate periodEndInclusive,
            int usableMonthCount,
            String fallbackReason) {

        public RecommendationSnapshot {
            if (method == null || method.isBlank() || method.length() > 80
                    || methodVersion == null || methodVersion.isBlank() || methodVersion.length() > 60) {
                throw new IllegalArgumentException("recommendation method and version are required");
            }
            Objects.requireNonNull(periodStart, "recommendation period start is required");
            Objects.requireNonNull(periodEndInclusive, "recommendation period end is required");
            if (periodStart.isAfter(periodEndInclusive)) {
                throw new IllegalArgumentException("recommendation period is invalid");
            }
            if (usableMonthCount < 0 || usableMonthCount > 3) {
                throw new IllegalArgumentException("usable recommendation month count must be between 0 and 3");
            }
            if (usableMonthCount == 0 && (fallbackReason == null || fallbackReason.isBlank())) {
                throw new IllegalArgumentException("a recommendation fallback reason is required without history");
            }
            if (usableMonthCount > 0 && fallbackReason != null) {
                throw new IllegalArgumentException("a recommendation fallback reason requires no usable history");
            }
            if (fallbackReason != null && fallbackReason.length() > 60) {
                throw new IllegalArgumentException("recommendation fallback reason is too long");
            }
        }
    }
}
