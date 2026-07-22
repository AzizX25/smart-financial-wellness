package com.azizx25.smartfinancialwellness.backend.user.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "financial_preference", schema = "smart_financial_wellness")
public class FinancialPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "primary_currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String primaryCurrency;

    @Column(name = "month_start_day", nullable = false)
    private short monthStartDay;

    @Column(name = "low_balance_threshold", precision = 19, scale = 4)
    private BigDecimal lowBalanceThreshold;

    @Column(name = "hide_home_amounts", nullable = false)
    private boolean hideHomeAmounts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected FinancialPreference() {
    }

    public FinancialPreference(AppUser user) {
        Instant now = Instant.now();
        this.user = user;
        this.primaryCurrency = "TND";
        this.monthStartDay = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPrimaryCurrency() {
        return primaryCurrency;
    }

    public void setPrimaryCurrency(String primaryCurrency) {
        this.primaryCurrency = primaryCurrency;
    }

    public short getMonthStartDay() {
        return monthStartDay;
    }

    public void setMonthStartDay(short monthStartDay) {
        this.monthStartDay = monthStartDay;
    }

    public BigDecimal getLowBalanceThreshold() {
        return lowBalanceThreshold;
    }

    public void setLowBalanceThreshold(BigDecimal lowBalanceThreshold) {
        this.lowBalanceThreshold = lowBalanceThreshold;
    }

    public boolean isHideHomeAmounts() {
        return hideHomeAmounts;
    }

    public void setHideHomeAmounts(boolean hideHomeAmounts) {
        this.hideHomeAmounts = hideHomeAmounts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getVersion() {
        return version;
    }
}
