package com.azizx25.smartfinancialwellness.category.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "category_prediction_evidence", schema = "smart_financial_wellness")
public class CategoryPredictionEvidence {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prediction_id", nullable = false)
    private CategoryPrediction prediction;

    @Column(name = "evidence_order", nullable = false)
    private short evidenceOrder;

    @Column(name = "evidence_url", nullable = false, length = 2048)
    private String evidenceUrl;

    @Column(name = "produced_at", nullable = false, updatable = false)
    private Instant producedAt;

    protected CategoryPredictionEvidence() {
    }

    CategoryPredictionEvidence(
            CategoryPrediction owner, short order, String url, Instant now) {
        id = UUID.randomUUID();
        prediction = owner;
        evidenceOrder = order;
        evidenceUrl = url;
        producedAt = now;
    }

    public String getEvidenceUrl() { return evidenceUrl; }
}
