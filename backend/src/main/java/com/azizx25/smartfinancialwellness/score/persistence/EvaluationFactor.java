package com.azizx25.smartfinancialwellness.score.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.FactorCode;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.FactorResult;
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
@Table(name = "evaluation_dimension", schema = "smart_financial_wellness")
public class EvaluationFactor {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wellness_evaluation_id", nullable = false, updatable = false)
    private WellnessEvaluation evaluation;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension_type", nullable = false, length = 40, updatable = false)
    private FactorCode dimensionType;

    @Column(precision = 7, scale = 4, updatable = false)
    private BigDecimal score;

    @Column(nullable = false, precision = 5, scale = 4, updatable = false)
    private BigDecimal weight;

    @Column(name = "coverage_rate", nullable = false, precision = 5, scale = 4, updatable = false)
    private BigDecimal coverageRate;

    @Column(name = "raw_value", precision = 19, scale = 4, updatable = false)
    private BigDecimal rawValue;

    @Column(nullable = false, length = 30, updatable = false)
    private String unit;

    @Column(nullable = false, updatable = false)
    private boolean available;

    @Column(nullable = false, length = 1000, updatable = false)
    private String explanation;

    @Column(name = "unavailable_reason", length = 500, updatable = false)
    private String unavailableReason;

    protected EvaluationFactor() {
    }

    EvaluationFactor(WellnessEvaluation parent, FactorResult result) {
        id = UUID.randomUUID();
        evaluation = parent;
        dimensionType = result.code();
        rawValue = result.rawValue();
        unit = result.unit();
        score = result.score();
        available = result.available();
        explanation = result.explanation();
        unavailableReason = result.unavailabilityReason();
        weight = BigDecimal.valueOf(result.code().baseWeight()).movePointLeft(2).setScale(4);
        coverageRate = result.available() ? BigDecimal.ONE.setScale(4) : BigDecimal.ZERO.setScale(4);
    }

    public FactorCode getDimensionType() { return dimensionType; }
    public BigDecimal getScore() { return score; }
    public int getBaseWeight() { return weight.movePointRight(2).intValueExact(); }
    public BigDecimal getRawValue() { return rawValue; }
    public String getUnit() { return unit; }
    public boolean isAvailable() { return available; }
    public String getExplanation() { return explanation; }
    public String getUnavailableReason() { return unavailableReason; }
}
