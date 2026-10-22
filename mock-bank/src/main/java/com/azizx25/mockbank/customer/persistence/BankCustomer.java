package com.azizx25.mockbank.customer.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bank_customer")
public class BankCustomer {

    @Id
    private UUID id;

    @Column(name = "external_customer_id", nullable = false, unique = true, length = 80)
    private String externalCustomerId;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected BankCustomer() {
    }

    public BankCustomer(
            String externalCustomerId,
            String displayName,
            String username,
            String passwordHash,
            String countryCode,
            OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.externalCustomerId = externalCustomerId;
        this.displayName = displayName;
        this.username = username;
        this.passwordHash = passwordHash;
        this.countryCode = countryCode;
        this.status = "ACTIVE";
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalCustomerId() {
        return externalCustomerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void updateProfile(String displayName, String username, String countryCode) {
        this.displayName = displayName;
        this.username = username;
        this.countryCode = countryCode;
    }

    public void resetPassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void activate() {
        this.status = "ACTIVE";
    }

    public void deactivate() {
        this.status = "INACTIVE";
    }
}
