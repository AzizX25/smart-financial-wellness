package com.azizx25.smartfinancialwellness.forecast.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "forecast_result", schema = "smart_financial_wellness")
public class ForecastResult {

    public enum MlStatus {
        APPLIED,
        UNSUPPORTED_CURRENCY,
        INSUFFICIENT_HISTORY,
        SERVICE_UNAVAILABLE,
        INVALID_RESPONSE,
        MODEL_VERSION_MISMATCH
    }

    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "time_zone", nullable = false, length = 80) private String timeZone;
    @Column(name = "forecast_month", nullable = false) private LocalDate forecastMonth;
    @Column(name = "local_cutoff_date", nullable = false) private LocalDate localCutoffDate;
    @Column(name = "snapshot_cutoff", nullable = false) private Instant snapshotCutoff;
    @Column(name = "starting_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal startingBalance;
    @Column(name = "deterministic_remaining_income", nullable = false, precision = 19, scale = 4)
    private BigDecimal deterministicRemainingIncome;
    @Column(name = "deterministic_remaining_expense", nullable = false, precision = 19, scale = 4)
    private BigDecimal deterministicRemainingExpense;
    @Column(name = "deterministic_remaining_net_flow", nullable = false, precision = 19, scale = 4)
    private BigDecimal deterministicRemainingNetFlow;
    @Column(name = "deterministic_predicted_end_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal deterministicPredictedEndBalance;
    @Enumerated(EnumType.STRING)
    @Column(name = "ml_status", nullable = false, length = 40) private MlStatus mlStatus;
    @Column(name = "ml_predicted_residual", precision = 19, scale = 4) private BigDecimal mlPredictedResidual;
    @Column(name = "final_predicted_end_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal finalPredictedEndBalance;
    @Column(name = "deterministic_method", nullable = false, length = 100) private String deterministicMethod;
    @Column(name = "deterministic_method_version", nullable = false, length = 40)
    private String deterministicMethodVersion;
    @Column(name = "model_id", length = 120) private String modelId;
    @Column(name = "model_version", length = 60) private String modelVersion;
    @Column(name = "feature_contract_version", length = 100) private String featureContractVersion;
    @Column(nullable = false) private boolean experimental;
    @Column(name = "synthetic_training_data", nullable = false) private boolean syntheticTrainingData;
    @Column(nullable = false) private boolean guaranteed;
    @Column(name = "canonical_input_fingerprint", nullable = false, length = 64)
    private String canonicalInputFingerprint;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Version @Column(nullable = false) private int version;

    protected ForecastResult() {
    }

    public ForecastResult(
            AppUser user, String currency, String timeZone, LocalDate forecastMonth,
            LocalDate localCutoffDate, Instant snapshotCutoff, BigDecimal startingBalance,
            BigDecimal deterministicRemainingIncome, BigDecimal deterministicRemainingExpense,
            BigDecimal deterministicRemainingNetFlow, BigDecimal deterministicPredictedEndBalance,
            MlStatus mlStatus, BigDecimal mlPredictedResidual, BigDecimal finalPredictedEndBalance,
            String deterministicMethod, String deterministicMethodVersion, String modelId,
            String modelVersion, String featureContractVersion, boolean experimental,
            boolean syntheticTrainingData, boolean guaranteed, String canonicalInputFingerprint,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.currency = currency;
        this.timeZone = timeZone;
        this.forecastMonth = forecastMonth;
        this.localCutoffDate = localCutoffDate;
        this.snapshotCutoff = snapshotCutoff;
        this.startingBalance = money(startingBalance);
        this.deterministicRemainingIncome = money(deterministicRemainingIncome);
        this.deterministicRemainingExpense = money(deterministicRemainingExpense);
        this.deterministicRemainingNetFlow = money(deterministicRemainingNetFlow);
        this.deterministicPredictedEndBalance = money(deterministicPredictedEndBalance);
        this.mlStatus = mlStatus;
        this.mlPredictedResidual = mlPredictedResidual == null ? null : money(mlPredictedResidual);
        this.finalPredictedEndBalance = money(finalPredictedEndBalance);
        this.deterministicMethod = deterministicMethod;
        this.deterministicMethodVersion = deterministicMethodVersion;
        this.modelId = modelId;
        this.modelVersion = modelVersion;
        this.featureContractVersion = featureContractVersion;
        this.experimental = experimental;
        this.syntheticTrainingData = syntheticTrainingData;
        this.guaranteed = guaranteed;
        this.canonicalInputFingerprint = canonicalInputFingerprint;
        this.createdAt = createdAt;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(4, RoundingMode.UNNECESSARY);
    }

    public UUID getId() { return id; }
    public String getCurrency() { return currency; }
    public String getTimeZone() { return timeZone; }
    public LocalDate getForecastMonth() { return forecastMonth; }
    public LocalDate getLocalCutoffDate() { return localCutoffDate; }
    public Instant getSnapshotCutoff() { return snapshotCutoff; }
    public BigDecimal getStartingBalance() { return startingBalance; }
    public BigDecimal getDeterministicRemainingIncome() { return deterministicRemainingIncome; }
    public BigDecimal getDeterministicRemainingExpense() { return deterministicRemainingExpense; }
    public BigDecimal getDeterministicRemainingNetFlow() { return deterministicRemainingNetFlow; }
    public BigDecimal getDeterministicPredictedEndBalance() { return deterministicPredictedEndBalance; }
    public MlStatus getMlStatus() { return mlStatus; }
    public BigDecimal getMlPredictedResidual() { return mlPredictedResidual; }
    public BigDecimal getFinalPredictedEndBalance() { return finalPredictedEndBalance; }
    public String getDeterministicMethod() { return deterministicMethod; }
    public String getDeterministicMethodVersion() { return deterministicMethodVersion; }
    public String getModelId() { return modelId; }
    public String getModelVersion() { return modelVersion; }
    public String getFeatureContractVersion() { return featureContractVersion; }
    public boolean isExperimental() { return experimental; }
    public boolean isSyntheticTrainingData() { return syntheticTrainingData; }
    public boolean isGuaranteed() { return guaranteed; }
    public String getCanonicalInputFingerprint() { return canonicalInputFingerprint; }
    public Instant getCreatedAt() { return createdAt; }
    public int getVersion() { return version; }
}
