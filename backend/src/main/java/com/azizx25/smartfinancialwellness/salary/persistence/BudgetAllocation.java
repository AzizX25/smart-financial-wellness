package com.azizx25.smartfinancialwellness.salary.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.salary.MonetaryAmounts;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "budget_allocation", schema = "smart_financial_wellness")
public class BudgetAllocation {

    public enum RecommendationOrigin { RULES, ML, USER, HYBRID }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monthly_plan_id", nullable = false)
    private MonthlyPlan monthlyPlan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "recommended_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal recommendedAmount;

    @Column(name = "confirmed_amount", precision = 19, scale = 4)
    private BigDecimal confirmedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation_origin", nullable = false, length = 30)
    private RecommendationOrigin recommendationOrigin;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected BudgetAllocation() {
    }

    BudgetAllocation(
            MonthlyPlan plan,
            Category expenseCategory,
            BigDecimal recommendation,
            RecommendationOrigin origin,
            Instant now) {
        id = UUID.randomUUID();
        monthlyPlan = Objects.requireNonNull(plan, "plan is required");
        category = requireEligibleCategory(plan, expenseCategory);
        recommendedAmount = MonetaryAmounts.requireNonNegativeInput(recommendation, "recommended allocation");
        recommendationOrigin = Objects.requireNonNull(origin, "recommendation origin is required");
        createdAt = Objects.requireNonNull(now, "now is required");
        updatedAt = now;
    }

    public void updateRecommendation(BigDecimal amount, RecommendationOrigin origin, Instant now) {
        recommendedAmount = MonetaryAmounts.requireNonNegativeInput(amount, "recommended allocation");
        recommendationOrigin = Objects.requireNonNull(origin, "recommendation origin is required");
        updatedAt = Objects.requireNonNull(now, "now is required");
    }

    public void confirm(BigDecimal amount, Instant now) {
        confirmedAmount = MonetaryAmounts.requireNonNegativeInput(amount, "confirmed allocation");
        confirmedAt = Objects.requireNonNull(now, "now is required");
        updatedAt = now;
    }

    public void clearConfirmation(Instant now) {
        confirmedAmount = null;
        confirmedAt = null;
        updatedAt = Objects.requireNonNull(now, "now is required");
    }

    private Category requireEligibleCategory(MonthlyPlan plan, Category candidate) {
        Objects.requireNonNull(candidate, "category is required");
        boolean visible = candidate.getUserId() == null || candidate.getUserId().equals(plan.getUserId());
        if (!visible || !candidate.isActive() || candidate.getNature() != Category.Nature.EXPENSE) {
            throw new IllegalArgumentException("category must be an active, visible expense category");
        }
        return candidate;
    }

    public UUID getId() { return id; }
    public Category getCategory() { return category; }
    public BigDecimal getRecommendedAmount() { return recommendedAmount; }
    public BigDecimal getConfirmedAmount() { return confirmedAmount; }
    public BigDecimal getEffectiveAmount() { return confirmedAmount == null ? recommendedAmount : confirmedAmount; }
    public RecommendationOrigin getRecommendationOrigin() { return recommendationOrigin; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
}
