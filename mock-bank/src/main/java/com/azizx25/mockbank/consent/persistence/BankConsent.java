package com.azizx25.mockbank.consent.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.azizx25.mockbank.client.persistence.OAuthClient;
import com.azizx25.mockbank.customer.persistence.BankCustomer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "bank_consent")
public class BankConsent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_customer_id", nullable = false)
    private BankCustomer bankCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "oauth_client_id", nullable = false)
    private OAuthClient oauthClient;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "granted_scopes", nullable = false, columnDefinition = "text[]")
    private String[] grantedScopes;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "consented_at", nullable = false)
    private OffsetDateTime consentedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revocation_reason", length = 255)
    private String revocationReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected BankConsent() {
    }

    public UUID getId() {
        return id;
    }

    public BankCustomer getBankCustomer() {
        return bankCustomer;
    }

    public OAuthClient getOauthClient() {
        return oauthClient;
    }

    public String[] getGrantedScopes() {
        return grantedScopes;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getConsentedAt() {
        return consentedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}