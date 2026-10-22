package com.azizx25.smartfinancialwellness.bank.persistence;

import java.time.Instant;
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

@Entity
@Table(name = "bank_connection", schema = "smart_financial_wellness")
public class BankConnection {

    public static final String MOCK_BANK_PROVIDER = "MOCK_BANK";

    public enum Status {
        PENDING, ACTIVE, REVOKED, ERROR, DISCONNECTED
    }

    public enum SyncStatus {
        IDLE, RUNNING, SUCCEEDED, FAILED
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "provider_code", nullable = false, length = 60)
    private String providerCode;

    @Column(name = "external_customer_id", length = 255)
    private String externalCustomerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 20)
    private SyncStatus syncStatus;

    @Column(name = "last_sync_attempted_at")
    private Instant lastSyncAttemptedAt;

    @Column(name = "last_sync_failure_code", length = 100)
    private String lastSyncFailureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected BankConnection() {
    }

    public BankConnection(AppUser user, String externalCustomerId, Instant now) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.providerCode = MOCK_BANK_PROVIDER;
        this.externalCustomerId = externalCustomerId;
        this.status = Status.ACTIVE;
        this.syncStatus = SyncStatus.IDLE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return user.getId();
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getExternalCustomerId() {
        return externalCustomerId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDisconnectedAt() {
        return disconnectedAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public SyncStatus getSyncStatus() {
        return syncStatus;
    }

    public Instant getLastSyncAttemptedAt() {
        return lastSyncAttemptedAt;
    }

    public String getLastSyncFailureCode() {
        return lastSyncFailureCode;
    }

    public void activate(String customerId, Instant now) {
        this.externalCustomerId = customerId;
        this.status = Status.ACTIVE;
        this.disconnectedAt = null;
        this.updatedAt = now;
    }

    public void markError(Instant now) {
        this.status = Status.ERROR;
        this.updatedAt = now;
    }

    public void disconnect(Instant now) {
        this.status = Status.DISCONNECTED;
        this.externalCustomerId = null;
        this.disconnectedAt = now;
        this.updatedAt = now;
    }

    public void startSynchronization(Instant now) {
        this.syncStatus = SyncStatus.RUNNING;
        this.lastSyncAttemptedAt = now;
        this.lastSyncFailureCode = null;
        this.updatedAt = now;
    }

    public void completeSynchronization(Instant now) {
        this.syncStatus = SyncStatus.SUCCEEDED;
        this.lastSyncedAt = now;
        this.lastSyncFailureCode = null;
        this.updatedAt = now;
    }

    public void failSynchronization(String safeFailureCode, Instant now) {
        this.syncStatus = SyncStatus.FAILED;
        this.lastSyncFailureCode = safeFailureCode;
        this.updatedAt = now;
    }
}
