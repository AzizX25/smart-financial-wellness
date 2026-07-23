package com.azizx25.smartfinancialwellness.auth.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "refresh_session", schema = "smart_financial_wellness")
public class RefreshSession {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "authentication_identity_id", nullable = false)
    private AuthenticationIdentity authenticationIdentity;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_session_id")
    private RefreshSession replacedBySession;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 100)
    private String revocationReason;

    @Column(name = "reuse_detected_at")
    private Instant reuseDetectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected RefreshSession() {
    }

    public RefreshSession(
            UUID id,
            AuthenticationIdentity authenticationIdentity,
            UUID familyId,
            String tokenHash,
            Instant expiresAt) {
        Instant now = Instant.now();
        this.id = id;
        this.authenticationIdentity = authenticationIdentity;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rotateTo(RefreshSession successor, Instant now) {
        this.replacedBySession = successor;
        this.lastUsedAt = now;
        this.revokedAt = now;
        this.revocationReason = "ROTATED";
    }

    public void revoke(Instant now, String reason) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revocationReason = reason;
        }
    }

    public UUID getId() {
        return id;
    }

    public AuthenticationIdentity getAuthenticationIdentity() {
        return authenticationIdentity;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public RefreshSession getReplacedBySession() {
        return replacedBySession;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public Instant getReuseDetectedAt() {
        return reuseDetectedAt;
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
