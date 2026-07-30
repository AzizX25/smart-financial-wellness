package com.azizx25.smartfinancialwellness.auth.persistence;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "authentication_identity", schema = "smart_financial_wellness")
public class AuthenticationIdentity {

    public enum Type {
        EMAIL
    }

    public enum Status {
        PENDING_VERIFICATION,
        ACTIVE,
        DISABLED
    }

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 20)
    private Type identityType;

    @Column(name = "normalized_identifier", nullable = false, length = 320)
    private String normalizedIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected AuthenticationIdentity() {
    }

    public AuthenticationIdentity(UUID id, AppUser user, String normalizedIdentifier) {
        Instant now = Instant.now();
        this.id = id;
        this.user = user;
        this.identityType = Type.EMAIL;
        this.normalizedIdentifier = normalizedIdentifier;
        this.status = Status.PENDING_VERIFICATION;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public Type getIdentityType() {
        return identityType;
    }

    public String getNormalizedIdentifier() {
        return normalizedIdentifier;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
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

    public void activate(Instant now) {
        this.status = Status.ACTIVE;
        this.verifiedAt = now;
        this.updatedAt = now;
    }
}
