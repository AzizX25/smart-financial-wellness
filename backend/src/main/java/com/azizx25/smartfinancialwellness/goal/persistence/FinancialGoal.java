package com.azizx25.smartfinancialwellness.goal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.salary.MonetaryAmounts;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "financial_goal", schema = "smart_financial_wellness")
public class FinancialGoal {

    public enum GoalType {
        EMERGENCY_FUND, PURCHASE, TRAVEL, EDUCATION, DEBT_REPAYMENT, RETIREMENT, OTHER
    }

    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }

    public enum Status { ACTIVE, PAUSED, ACHIEVED, CANCELLED, ARCHIVED }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_type", nullable = false, length = 40)
    private GoalType goalType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "target_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal targetAmount;

    @Column(name = "initial_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal initialAmount;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected FinancialGoal() {
    }

    public FinancialGoal(
            AppUser owner,
            String goalName,
            String goalDescription,
            GoalType type,
            String goalCurrency,
            BigDecimal target,
            BigDecimal initial,
            LocalDate desiredTargetDate,
            Priority goalPriority,
            Instant now) {
        id = UUID.randomUUID();
        user = Objects.requireNonNull(owner, "owner is required");
        name = requireText(goalName, 160, "name");
        description = optionalText(goalDescription, 1000, "description");
        goalType = Objects.requireNonNull(type, "goal type is required");
        currency = MonetaryAmounts.requireCurrency(goalCurrency);
        targetAmount = positiveTarget(target);
        initialAmount = validInitialAmount(initial, targetAmount);
        targetDate = desiredTargetDate;
        priority = Objects.requireNonNull(goalPriority, "priority is required");
        status = Status.ACTIVE;
        createdAt = Objects.requireNonNull(now, "now is required");
        updatedAt = now;
    }

    public void changeCurrency(String newCurrency) {
        currency = MonetaryAmounts.requireCurrency(newCurrency);
    }

    public void replaceEditableFields(
            String goalName,
            String goalDescription,
            GoalType type,
            String goalCurrency,
            BigDecimal target,
            BigDecimal initial,
            LocalDate desiredTargetDate,
            Priority goalPriority,
            Instant now) {
        name = requireText(goalName, 160, "name");
        description = optionalText(goalDescription, 1000, "description");
        goalType = Objects.requireNonNull(type, "goal type is required");
        currency = MonetaryAmounts.requireCurrency(goalCurrency);
        targetAmount = positiveTarget(target);
        initialAmount = validInitialAmount(initial, targetAmount);
        targetDate = desiredTargetDate;
        priority = Objects.requireNonNull(goalPriority, "priority is required");
        updatedAt = Objects.requireNonNull(now, "update time is required");
    }

    public void changeStatus(Status newStatus, Instant now) {
        status = Objects.requireNonNull(newStatus, "status is required");
        updatedAt = Objects.requireNonNull(now, "status change time is required");
    }

    public boolean synchronizeAchievement(boolean completed, Instant now) {
        Status previous = status;
        if (completed && status != Status.CANCELLED && status != Status.ARCHIVED) {
            status = Status.ACHIEVED;
        } else if (!completed && status == Status.ACHIEVED) {
            status = Status.ACTIVE;
        }
        if (status != previous) {
            updatedAt = Objects.requireNonNull(now, "status change time is required");
            return true;
        }
        return false;
    }

    public boolean archive(Instant now) {
        if (status == Status.ARCHIVED) {
            return false;
        }
        status = Status.ARCHIVED;
        updatedAt = Objects.requireNonNull(now, "archive time is required");
        return true;
    }

    private static BigDecimal positiveTarget(BigDecimal value) {
        BigDecimal target = MonetaryAmounts.requireNonNegativeInput(value, "target amount");
        if (target.signum() == 0) {
            throw new IllegalArgumentException("target amount must be positive");
        }
        return target;
    }

    private static BigDecimal validInitialAmount(BigDecimal value, BigDecimal target) {
        BigDecimal initial = MonetaryAmounts.requireNonNegativeInput(value, "initial amount");
        if (initial.compareTo(target) > 0) {
            throw new IllegalArgumentException("initial amount cannot exceed target amount");
        }
        return initial;
    }

    private static String requireText(String value, int maximumLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }

    private static String optionalText(String value, int maximumLength, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return user.getId(); }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public GoalType getGoalType() { return goalType; }
    public String getCurrency() { return currency; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public BigDecimal getInitialAmount() { return initialAmount; }
    public LocalDate getTargetDate() { return targetDate; }
    public Priority getPriority() { return priority; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
}
