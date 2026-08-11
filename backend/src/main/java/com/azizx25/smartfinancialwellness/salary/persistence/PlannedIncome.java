package com.azizx25.smartfinancialwellness.salary.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

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
@Table(name = "planned_income", schema = "smart_financial_wellness")
public class PlannedIncome {

    public enum IncomeType { SALARY, FREELANCE, BENEFIT, GIFT, OTHER }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monthly_plan_id", nullable = false)
    private MonthlyPlan monthlyPlan;

    @Column(nullable = false, length = 160)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "income_type", nullable = false, length = 30)
    private IncomeType incomeType;

    @Column(name = "expected_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedAmount;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(nullable = false)
    private boolean recurring;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected PlannedIncome() {
    }

    PlannedIncome(
            MonthlyPlan plan,
            String incomeLabel,
            IncomeType type,
            BigDecimal amount,
            LocalDate date,
            boolean isRecurring,
            Instant now) {
        id = UUID.randomUUID();
        monthlyPlan = Objects.requireNonNull(plan, "plan is required");
        label = requireLabel(incomeLabel);
        incomeType = Objects.requireNonNull(type, "income type is required");
        expectedAmount = MonetaryAmounts.requireNonNegativeInput(amount, "expected income");
        expectedDate = date;
        recurring = isRecurring;
        createdAt = Objects.requireNonNull(now, "now is required");
        updatedAt = now;
    }

    public void update(String incomeLabel, IncomeType type, BigDecimal amount, LocalDate date, boolean isRecurring, Instant now) {
        label = requireLabel(incomeLabel);
        incomeType = Objects.requireNonNull(type, "income type is required");
        expectedAmount = MonetaryAmounts.requireNonNegativeInput(amount, "expected income");
        expectedDate = date;
        recurring = isRecurring;
        updatedAt = Objects.requireNonNull(now, "now is required");
    }

    private String requireLabel(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("income label must contain 1 to 160 characters");
        }
        return value.strip();
    }

    public UUID getId() { return id; }
    public String getLabel() { return label; }
    public IncomeType getIncomeType() { return incomeType; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public LocalDate getExpectedDate() { return expectedDate; }
    public boolean isRecurring() { return recurring; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
}
