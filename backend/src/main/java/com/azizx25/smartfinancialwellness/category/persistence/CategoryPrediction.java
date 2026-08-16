package com.azizx25.smartfinancialwellness.category.persistence;

import java.time.Instant;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "category_prediction", schema = "smart_financial_wellness")
public class CategoryPrediction {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private TransactionRecord transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "prediction_method", nullable = false, length = 30)
    private String predictionMethod;

    @Column(length = 1000)
    private String explanation;

    @Column(name = "produced_at", nullable = false, updatable = false)
    private Instant producedAt;

    protected CategoryPrediction() {
    }

    public CategoryPrediction(TransactionRecord record, Category target, String ruleCode, Instant now) {
        id = UUID.randomUUID();
        transaction = record;
        category = target;
        predictionMethod = "RULES";
        explanation = "Matched deterministic rule " + ruleCode;
        producedAt = now;
    }

    public UUID getId() { return id; }
}
