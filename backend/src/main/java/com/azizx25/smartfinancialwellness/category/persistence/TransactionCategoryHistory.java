package com.azizx25.smartfinancialwellness.category.persistence;

import java.time.Instant;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "transaction_category_history", schema = "smart_financial_wellness")
public class TransactionCategoryHistory {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private TransactionRecord transaction;

    @Column(name = "previous_category_id")
    private UUID previousCategoryId;

    @Column(name = "new_category_id")
    private UUID newCategoryId;

    @Column(name = "previous_source", nullable = false, length = 30)
    private String previousSource;

    @Column(name = "new_source", nullable = false, length = 30)
    private String newSource;

    @Column(name = "matched_rule_id")
    private UUID matchedRuleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private AppUser actor;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected TransactionCategoryHistory() {
    }

    public TransactionCategoryHistory(
            TransactionRecord record,
            UUID previousCategory,
            UUID newCategory,
            String previousAssignmentSource,
            String newAssignmentSource,
            UUID ruleId,
            AppUser user,
            Instant now) {
        id = UUID.randomUUID();
        transaction = record;
        previousCategoryId = previousCategory;
        newCategoryId = newCategory;
        previousSource = previousAssignmentSource;
        newSource = newAssignmentSource;
        matchedRuleId = ruleId;
        actor = user;
        changedAt = now;
    }
}
