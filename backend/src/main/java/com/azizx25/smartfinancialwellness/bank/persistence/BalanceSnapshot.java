package com.azizx25.smartfinancialwellness.bank.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "balance_snapshot", schema = "smart_financial_wellness")
public class BalanceSnapshot {

    public enum SourceType {
        MANUAL, FILE_IMPORT, BANK_SYNC
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "financial_account_id", nullable = false)
    private FinancialAccount financialAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_job_id")
    private SyncJob syncJob;

    @Column(name = "available_balance", precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "ledger_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal ledgerBalance;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BalanceSnapshot() {
    }

    public static BalanceSnapshot declaredManually(FinancialAccount account, Instant now) {
        BalanceSnapshot snapshot = new BalanceSnapshot();
        snapshot.id = UUID.randomUUID();
        snapshot.financialAccount = account;
        snapshot.ledgerBalance = account.getCurrentBalance();
        snapshot.currency = account.getCurrency();
        snapshot.sourceType = SourceType.MANUAL;
        snapshot.observedAt = now;
        snapshot.createdAt = now;
        return snapshot;
    }

    public BalanceSnapshot(
            FinancialAccount account,
            SyncJob job,
            BigDecimal currentBalance,
            BigDecimal availableBalance,
            Instant observedAt) {
        this.id = UUID.randomUUID();
        this.financialAccount = account;
        this.syncJob = job;
        this.ledgerBalance = scale(currentBalance);
        this.availableBalance = availableBalance == null ? null : scale(availableBalance);
        this.currency = account.getCurrency();
        this.sourceType = SourceType.BANK_SYNC;
        this.observedAt = observedAt;
        this.createdAt = observedAt;
    }

    private BigDecimal scale(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.UNNECESSARY);
    }

    public UUID getId() {
        return id;
    }

    public UUID getFinancialAccountId() {
        return financialAccount.getId();
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getLedgerBalance() {
        return ledgerBalance;
    }

    public String getCurrency() {
        return currency;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
