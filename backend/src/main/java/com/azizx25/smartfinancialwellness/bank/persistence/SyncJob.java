package com.azizx25.smartfinancialwellness.bank.persistence;

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

@Entity
@Table(name = "sync_job", schema = "smart_financial_wellness")
public class SyncJob {

    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_connection_id", nullable = false)
    private BankConnection bankConnection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "authorization_id", nullable = false)
    private BankConnectionAuthorization authorization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "read_count", nullable = false)
    private int readCount;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    @Column(name = "safe_error_code", length = 100)
    private String safeErrorCode;

    @Column(name = "accounts_created", nullable = false)
    private int accountsCreated;

    @Column(name = "accounts_updated", nullable = false)
    private int accountsUpdated;

    @Column(name = "transactions_created", nullable = false)
    private int transactionsCreated;

    @Column(name = "transactions_updated", nullable = false)
    private int transactionsUpdated;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SyncJob() {
    }

    public SyncJob(
            BankConnection bankConnection,
            BankConnectionAuthorization authorization,
            Instant now) {
        this.id = UUID.randomUUID();
        this.bankConnection = bankConnection;
        this.authorization = authorization;
        this.status = Status.RUNNING;
        this.startedAt = now;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConnectionId() {
        return bankConnection.getId();
    }

    public Status getStatus() {
        return status;
    }

    public int getAccountsCreated() {
        return accountsCreated;
    }

    public int getAccountsUpdated() {
        return accountsUpdated;
    }

    public int getTransactionsCreated() {
        return transactionsCreated;
    }

    public int getTransactionsUpdated() {
        return transactionsUpdated;
    }

    public String getSafeErrorCode() {
        return safeErrorCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void complete(
            int accountCount,
            int transactionCount,
            int createdAccounts,
            int updatedAccounts,
            int createdTransactions,
            int updatedTransactions,
            Instant now) {
        status = Status.SUCCEEDED;
        readCount = accountCount + transactionCount;
        accountsCreated = createdAccounts;
        accountsUpdated = updatedAccounts;
        transactionsCreated = createdTransactions;
        transactionsUpdated = updatedTransactions;
        createdCount = createdAccounts + createdTransactions;
        duplicateCount = updatedAccounts + updatedTransactions;
        safeErrorCode = null;
        completedAt = now;
    }

    public void fail(String failureCode, Instant now) {
        status = Status.FAILED;
        safeErrorCode = failureCode;
        completedAt = now;
    }
}
