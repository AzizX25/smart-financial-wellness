package com.azizx25.smartfinancialwellness.category.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "category_prediction", schema = "smart_financial_wellness")
public class CategoryPrediction {

    public enum ReviewStatus { PENDING, ACCEPTED, REJECTED, AUTO_APPLIED }

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

    @Column(name = "canonical_merchant", length = 160)
    private String canonicalMerchant;

    @Column(name = "prediction_score", precision = 5, scale = 4)
    private BigDecimal predictionScore;

    @Column(name = "model_id", length = 100)
    private String modelId;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "feature_version", length = 100)
    private String featureVersion;

    @Column(name = "provider_id", length = 60)
    private String providerId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private ReviewStatus reviewStatus;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(length = 1000)
    private String explanation;

    @Column(name = "produced_at", nullable = false, updatable = false)
    private Instant producedAt;

    @OneToMany(mappedBy = "prediction", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("evidenceOrder ASC")
    private List<CategoryPredictionEvidence> evidence = new ArrayList<>();

    protected CategoryPrediction() {
    }

    public CategoryPrediction(TransactionRecord record, Category target, String ruleCode, Instant now) {
        id = UUID.randomUUID();
        transaction = record;
        category = target;
        predictionMethod = "RULES";
        explanation = "Matched deterministic rule " + ruleCode;
        producedAt = now;
        reviewStatus = ReviewStatus.AUTO_APPLIED;
        reviewedAt = now;
    }

    public CategoryPrediction(
            TransactionRecord record, Category target, String merchant, BigDecimal score,
            String method, String merchantModelId, String merchantModelVersion,
            String merchantFeatureVersion, Instant now) {
        id = UUID.randomUUID();
        transaction = record;
        category = target;
        predictionMethod = "ML";
        canonicalMerchant = merchant;
        predictionScore = score;
        modelId = merchantModelId;
        modelVersion = merchantModelVersion;
        featureVersion = merchantFeatureVersion;
        explanation = method;
        producedAt = now;
        reviewStatus = ReviewStatus.PENDING;
    }

    public CategoryPrediction(
            TransactionRecord record, Category target, String merchant,
            String merchantProviderId, String merchantModelId, String merchantModelVersion,
            String merchantFeatureVersion, String rationale, List<String> evidenceUrls, Instant now) {
        id = UUID.randomUUID();
        transaction = record;
        category = target;
        predictionMethod = "LLM";
        canonicalMerchant = merchant;
        providerId = merchantProviderId;
        modelId = merchantModelId;
        modelVersion = merchantModelVersion;
        featureVersion = merchantFeatureVersion;
        explanation = rationale;
        producedAt = now;
        reviewStatus = ReviewStatus.PENDING;
        short order = 1;
        for (String url : evidenceUrls) {
            evidence.add(new CategoryPredictionEvidence(this, order++, url, now));
        }
    }

    public void accept(Instant now) { reviewStatus = ReviewStatus.ACCEPTED; reviewedAt = now; }
    public void reject(Instant now) { reviewStatus = ReviewStatus.REJECTED; reviewedAt = now; }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transaction.getId(); }
    public TransactionRecord getTransaction() { return transaction; }
    public Category getCategory() { return category; }
    public String getPredictionMethod() { return predictionMethod; }
    public String getCanonicalMerchant() { return canonicalMerchant; }
    public BigDecimal getPredictionScore() { return predictionScore; }
    public String getModelId() { return modelId; }
    public String getModelVersion() { return modelVersion; }
    public String getFeatureVersion() { return featureVersion; }
    public String getProviderId() { return providerId; }
    public String getExplanation() { return explanation; }
    public List<String> getEvidenceUrls() {
        return evidence.stream().map(CategoryPredictionEvidence::getEvidenceUrl).toList();
    }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public Instant getProducedAt() { return producedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
}
