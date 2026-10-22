package com.azizx25.smartfinancialwellness.category.persistence;

import java.time.Instant;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
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

@Entity
@Table(name = "categorization_review", schema = "smart_financial_wellness")
public class CategorizationReview {

    public enum State { SUGGESTION_PENDING, NEEDS_REVIEW, EXCLUDED, RESOLVED }
    public enum Resolution { SUGGESTION_ACCEPTED, MANUAL_ASSIGNMENT }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private TransactionRecord transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private State state;

    @Column(name = "reason_code", nullable = false, length = 60)
    private String reasonCode;

    @Column(name = "source_prediction_id")
    private UUID sourcePredictionId;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private Resolution resolution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_category_id")
    private Category resolvedCategory;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected CategorizationReview() {
    }

    public CategorizationReview(
            TransactionRecord record, State initialState, String reason, UUID predictionId, Instant now) {
        id = UUID.randomUUID();
        transaction = record;
        user = record.getUser();
        state = initialState;
        reasonCode = reason;
        sourcePredictionId = predictionId;
        createdAt = now;
        updatedAt = now;
    }

    public void updateOpen(State newState, String reason, UUID predictionId) {
        if (state == State.RESOLVED) throw new IllegalStateException("Resolved reviews are immutable");
        state = newState;
        reasonCode = reason;
        sourcePredictionId = predictionId;
    }

    public void resolve(Resolution result, Category category, UUID predictionId, Instant now) {
        state = State.RESOLVED;
        reasonCode = result.name();
        sourcePredictionId = predictionId;
        resolution = result;
        resolvedCategory = category;
        resolvedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transaction.getId(); }
    public UUID getUserId() { return user.getId(); }
    public State getState() { return state; }
    public String getReasonCode() { return reasonCode; }
    public UUID getSourcePredictionId() { return sourcePredictionId; }
    public Instant getUpdatedAt() { return updatedAt; }
}
