package com.azizx25.smartfinancialwellness.auth.persistence;

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
@Table(name = "account_action_token", schema = "smart_financial_wellness")
public class AccountActionToken {

    public enum ActionType {
        EMAIL_VERIFICATION,
        PASSWORD_RESET
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "authentication_identity_id", nullable = false)
    private AuthenticationIdentity authenticationIdentity;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private ActionType actionType;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountActionToken() {
    }

    public AccountActionToken(
            UUID id,
            AuthenticationIdentity authenticationIdentity,
            ActionType actionType,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt) {
        this.id = id;
        this.authenticationIdentity = authenticationIdentity;
        this.actionType = actionType;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public boolean isAvailableFor(ActionType expectedActionType, Instant now) {
        return actionType == expectedActionType
                && usedAt == null
                && invalidatedAt == null
                && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public AuthenticationIdentity getAuthenticationIdentity() {
        return authenticationIdentity;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
