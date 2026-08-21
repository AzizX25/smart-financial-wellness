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
@Table(name = "categorization_review_candidate", schema = "smart_financial_wellness")
public class CategorizationReviewCandidate {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private CategorizationReview review;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "candidate_rank", nullable = false)
    private short candidateRank;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CategorizationReviewCandidate() {
    }

    public CategorizationReviewCandidate(
            CategorizationReview owner, Category suggestedCategory, short rank, Instant now) {
        id = UUID.randomUUID();
        review = owner;
        category = suggestedCategory;
        candidateRank = rank;
        createdAt = now;
    }

    public UUID getReviewId() { return review.getId(); }
    public Category getCategory() { return category; }
    public short getCandidateRank() { return candidateRank; }
}
