package com.azizx25.smartfinancialwellness.backend.auth.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
@Table(name = "password_credential", schema = "smart_financial_wellness")
public class PasswordCredential {

    @Id
    @Column(name = "authentication_identity_id")
    private UUID authenticationIdentityId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "authentication_identity_id", nullable = false)
    private AuthenticationIdentity authenticationIdentity;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_algorithm", nullable = false, length = 60)
    private String passwordAlgorithm;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "password_parameters", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> passwordParameters;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected PasswordCredential() {
    }

    public PasswordCredential(AuthenticationIdentity authenticationIdentity, String passwordHash, int strength) {
        Instant now = Instant.now();
        this.authenticationIdentity = authenticationIdentity;
        this.passwordHash = passwordHash;
        this.passwordAlgorithm = "BCRYPT";
        this.passwordParameters = new LinkedHashMap<>();
        this.passwordParameters.put("strength", strength);
        this.passwordChangedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getAuthenticationIdentityId() {
        return authenticationIdentityId;
    }

    public AuthenticationIdentity getAuthenticationIdentity() {
        return authenticationIdentity;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPasswordAlgorithm() {
        return passwordAlgorithm;
    }

    public Map<String, Object> getPasswordParameters() {
        return passwordParameters;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
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
