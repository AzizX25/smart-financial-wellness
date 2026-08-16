package com.azizx25.smartfinancialwellness.goal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.salary.MonetaryAmounts;
import com.azizx25.smartfinancialwellness.salary.persistence.MonthlyPlan;
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
@Table(name = "goal_allocation", schema = "smart_financial_wellness")
public class GoalAllocation {

    public enum Status { PROPOSED, CONFIRMED, CANCELLED }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monthly_plan_id", nullable = false, updatable = false)
    private MonthlyPlan monthlyPlan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "financial_goal_id", nullable = false, updatable = false)
    private FinancialGoal financialGoal;

    @Column(name = "planned_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal plannedAmount;

    @Column(name = "confirmed_amount", precision = 19, scale = 4)
    private BigDecimal confirmedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected GoalAllocation() {
    }

    public GoalAllocation(
            MonthlyPlan plan,
            FinancialGoal goal,
            BigDecimal proposedAmount,
            Instant now) {
        id = UUID.randomUUID();
        monthlyPlan = Objects.requireNonNull(plan, "monthly plan is required");
        financialGoal = Objects.requireNonNull(goal, "financial goal is required");
        plannedAmount = MonetaryAmounts.requireNonNegativeInput(proposedAmount, "planned amount");
        status = Status.PROPOSED;
        createdAt = Objects.requireNonNull(now, "now is required");
        updatedAt = now;
    }

    public void confirm(BigDecimal amount, Instant now) {
        BigDecimal normalizedAmount = MonetaryAmounts.requireNonNegativeInput(amount, "confirmed amount");
        if (status == Status.CONFIRMED
                && confirmedAmount != null
                && confirmedAmount.compareTo(normalizedAmount) == 0) {
            return;
        }
        confirmedAmount = normalizedAmount;
        status = Status.CONFIRMED;
        confirmedAt = Objects.requireNonNull(now, "confirmation time is required");
        updatedAt = now;
    }

    public void replaceAndConfirm(BigDecimal amount, Instant now) {
        BigDecimal normalizedAmount = MonetaryAmounts.requireNonNegativeInput(amount, "confirmed amount");
        if (status == Status.CONFIRMED
                && plannedAmount.compareTo(normalizedAmount) == 0
                && confirmedAmount != null
                && confirmedAmount.compareTo(normalizedAmount) == 0) {
            return;
        }
        plannedAmount = normalizedAmount;
        confirmedAmount = normalizedAmount;
        status = Status.CONFIRMED;
        confirmedAt = Objects.requireNonNull(now, "confirmation time is required");
        updatedAt = now;
    }

    public void cancel(Instant now) {
        confirmedAmount = null;
        confirmedAt = null;
        status = Status.CANCELLED;
        updatedAt = Objects.requireNonNull(now, "cancellation time is required");
    }

    public UUID getId() { return id; }
    public UUID getMonthlyPlanId() { return monthlyPlan.getId(); }
    public UUID getFinancialGoalId() { return financialGoal.getId(); }
    public FinancialGoal getFinancialGoal() { return financialGoal; }
    public BigDecimal getPlannedAmount() { return plannedAmount; }
    public BigDecimal getConfirmedAmount() { return confirmedAmount; }
    public Status getStatus() { return status; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
}
