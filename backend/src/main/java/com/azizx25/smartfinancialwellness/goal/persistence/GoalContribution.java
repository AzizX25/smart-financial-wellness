package com.azizx25.smartfinancialwellness.goal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
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

@Entity
@Table(name = "goal_contribution", schema = "smart_financial_wellness")
public class GoalContribution {

    public enum Source { MANUAL, TRANSACTION }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "financial_goal_id", nullable = false, updatable = false)
    private FinancialGoal financialGoal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", updatable = false)
    private TransactionRecord transaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Source source;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "contribution_date", nullable = false, updatable = false)
    private LocalDate contributionDate;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GoalContribution() {
    }

    public GoalContribution(
            FinancialGoal goal,
            Source contributionSource,
            TransactionRecord linkedTransaction,
            BigDecimal contributionAmount,
            LocalDate date,
            String contributionNote,
            Instant now) {
        id = UUID.randomUUID();
        financialGoal = Objects.requireNonNull(goal, "financial goal is required");
        source = Objects.requireNonNull(contributionSource, "source is required");
        transaction = linkedTransaction;
        if ((source == Source.TRANSACTION) != (transaction != null)) {
            throw new IllegalArgumentException("transaction contributions require a linked transaction");
        }
        amount = MonetaryAmounts.requireNonNegativeInput(contributionAmount, "contribution amount");
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("contribution amount must be positive");
        }
        contributionDate = Objects.requireNonNull(date, "contribution date is required");
        note = optionalNote(contributionNote);
        createdAt = Objects.requireNonNull(now, "now is required");
    }

    private static String optionalNote(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("note is too long");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    public UUID getId() { return id; }
    public UUID getFinancialGoalId() { return financialGoal.getId(); }
    public TransactionRecord getTransaction() { return transaction; }
    public Source getSource() { return source; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getContributionDate() { return contributionDate; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
