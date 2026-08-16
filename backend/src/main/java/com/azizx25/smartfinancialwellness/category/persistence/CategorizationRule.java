package com.azizx25.smartfinancialwellness.category.persistence;

import java.util.UUID;

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
@Table(name = "categorization_rule", schema = "smart_financial_wellness")
public class CategorizationRule {

    public enum MatchField { DESCRIPTION, COUNTERPARTY }
    public enum MatchType { EXACT, CONTAINS }

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_field", nullable = false, length = 20)
    private MatchField matchField;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    private MatchType matchType;

    @Column(name = "normalized_pattern", nullable = false, length = 500)
    private String normalizedPattern;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Category.Direction direction;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean active;

    protected CategorizationRule() {
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public Category getCategory() { return category; }
    public MatchField getMatchField() { return matchField; }
    public MatchType getMatchType() { return matchType; }
    public String getNormalizedPattern() { return normalizedPattern; }
    public Category.Direction getDirection() { return direction; }
    public int getPriority() { return priority; }
}
