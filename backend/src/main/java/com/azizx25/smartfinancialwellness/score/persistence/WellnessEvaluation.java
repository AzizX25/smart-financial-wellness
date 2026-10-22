package com.azizx25.smartfinancialwellness.score.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.Band;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.Calculation;
import com.azizx25.smartfinancialwellness.score.FinancialWellnessScoreCalculator.FactorResult;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "wellness_evaluation", schema = "smart_financial_wellness")
public class WellnessEvaluation {

    public enum Status { DRAFT, PRODUCED, OBSOLETE, FAILED }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "global_score", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal globalScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Band level;

    @Column(name = "coverage_rate", nullable = false, precision = 5, scale = 4, updatable = false)
    private BigDecimal coverageRate;

    @Column(name = "method_version", nullable = false, length = 100, updatable = false)
    private String methodVersion;

    @Column(name = "as_of_date", nullable = false, updatable = false)
    private LocalDate asOfDate;

    @Column(name = "time_zone", nullable = false, length = 80, updatable = false)
    private String timeZone;

    @Column(name = "available_base_weight", nullable = false, updatable = false)
    private short availableBaseWeight;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "input_fingerprint", nullable = false, length = 64, updatable = false,
            columnDefinition = "char(64)")
    private String inputFingerprint;

    @Column(name = "produced_at")
    private Instant producedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false, insertable = false, updatable = false)
    private int version;

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL)
    @OrderBy("dimensionType ASC")
    private List<EvaluationFactor> factors = new ArrayList<>();

    protected WellnessEvaluation() {
    }

    public WellnessEvaluation(
            AppUser owner, String scoreCurrency, LocalDate calculationDate, String zoneId,
            LocalDate historyStart, LocalDate historyEnd, String calculationVersion,
            String fingerprint, Calculation calculation, Instant now) {
        if (!calculation.available() || calculation.score() == null || calculation.band() == null) {
            throw new IllegalArgumentException("only available scores can be persisted");
        }
        id = UUID.randomUUID();
        user = Objects.requireNonNull(owner, "owner is required");
        currency = Objects.requireNonNull(scoreCurrency, "currency is required");
        asOfDate = Objects.requireNonNull(calculationDate, "as-of date is required");
        timeZone = Objects.requireNonNull(zoneId, "timezone is required");
        periodStart = Objects.requireNonNull(historyStart, "history start is required");
        periodEnd = Objects.requireNonNull(historyEnd, "history end is required");
        methodVersion = Objects.requireNonNull(calculationVersion, "calculation version is required");
        inputFingerprint = Objects.requireNonNull(fingerprint, "input fingerprint is required");
        globalScore = calculation.score();
        level = calculation.band();
        availableBaseWeight = (short) calculation.availableBaseWeight();
        coverageRate = BigDecimal.valueOf(availableBaseWeight)
                .movePointLeft(2).setScale(4);
        status = Status.DRAFT;
        createdAt = Objects.requireNonNull(now, "creation time is required");
        updatedAt = now;
        calculation.factors().stream()
                .sorted(Comparator.comparing(factor -> factor.code().name()))
                .map(factor -> new EvaluationFactor(this, factor))
                .forEach(factors::add);
    }

    public void publish(Instant now) {
        if (status != Status.DRAFT) {
            throw new IllegalStateException("only a draft score can be published");
        }
        status = Status.PRODUCED;
        producedAt = Objects.requireNonNull(now, "production time is required");
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return user.getId(); }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public BigDecimal getGlobalScore() { return globalScore; }
    public Band getLevel() { return level; }
    public BigDecimal getCoverageRate() { return coverageRate; }
    public String getMethodVersion() { return methodVersion; }
    public LocalDate getAsOfDate() { return asOfDate; }
    public String getTimeZone() { return timeZone; }
    public int getAvailableBaseWeight() { return availableBaseWeight; }
    public String getInputFingerprint() { return inputFingerprint; }
    public Instant getProducedAt() { return producedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<EvaluationFactor> getFactors() { return List.copyOf(factors); }
}
