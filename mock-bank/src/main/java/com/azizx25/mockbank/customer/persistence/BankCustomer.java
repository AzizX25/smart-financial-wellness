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

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected BankCustomer() {
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

    public String getCountryCode() {
        return countryCode;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}