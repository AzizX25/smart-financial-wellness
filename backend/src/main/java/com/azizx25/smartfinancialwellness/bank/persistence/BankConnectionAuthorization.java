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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bank_connection_authorization", schema = "smart_financial_wellness")
public class BankConnectionAuthorization {

    public enum Status {
        ACTIVE, REVOKED, EXPIRED, SUPERSEDED
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_connection_id", nullable = false)
    private BankConnection bankConnection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "secret_reference", length = 500)
    private String secretReference;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "granted_scopes", nullable = false, columnDefinition = "varchar(40)[]")
    private String[] grantedScopes;

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 255)
    private String revocationReason;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_by_authorization_id")
    private BankConnectionAuthorization supersededByAuthorization;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "provider_access_token", columnDefinition = "text")
    private String providerAccessToken;

    protected BankConnectionAuthorization() {
    }

    public BankConnectionAuthorization(
            BankConnection bankConnection,
            String providerAccessToken,
            Instant expiresAt,
            Instant now) {
        this.id = UUID.randomUUID();
        this.bankConnection = bankConnection;
        this.status = Status.ACTIVE;
        this.secretReference = "database:bank-authorization:" + id;
        this.grantedScopes = new String[]{
                "ACCOUNTS_READ",
                "BALANCES_READ",
                "TRANSACTIONS_READ"
        };
        this.consentedAt = now;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.providerAccessToken = providerAccessToken;
    }

    public UUID getId() {
        return id;
    }

    public BankConnection getBankConnection() {
        return bankConnection;
    }

    public Status getStatus() {
        return status;
    }

    public String[] getGrantedScopes() {
        return grantedScopes.clone();
    }

    public Instant getConsentedAt() {
        return consentedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getProviderAccessToken() {
        return providerAccessToken;
    }

    public boolean isUsableAt(Instant now) {
        return status == Status.ACTIVE
                && providerAccessToken != null
                && expiresAt.isAfter(now);
    }

    public boolean grants(String scope) {
        return java.util.Arrays.asList(grantedScopes).contains(scope);
    }

    public void revoke(Instant now, String reason) {
        this.status = Status.REVOKED;
        this.revokedAt = now;
        this.revocationReason = reason;
        clearProviderCredential();
    }

    public void expire() {
        this.status = Status.EXPIRED;
        clearProviderCredential();
    }

    private void clearProviderCredential() {
        this.providerAccessToken = null;
        this.secretReference = null;
    }
}
