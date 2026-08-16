package com.azizx25.mockbank.account.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bank_account")
public class BankAccount {

    @Id
    private UUID id;

    @Column(name = "bank_customer_id", nullable = false)
    private UUID bankCustomerId;

    @Column(name = "external_account_id", nullable = false, length = 100)
    private String externalAccountId;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "current_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentBalance;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected BankAccount() {
    }

    public BankAccount(
            UUID bankCustomerId,
            String externalAccountId,
            String displayName,
            String accountType,
            String currency,
            OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.bankCustomerId = bankCustomerId;
        this.externalAccountId = externalAccountId;
        this.displayName = displayName;
        this.accountType = accountType;
        this.currency = currency;
        this.currentBalance = BigDecimal.ZERO.setScale(4);
        this.availableBalance = BigDecimal.ZERO.setScale(4);
        this.status = "ACTIVE";
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBankCustomerId() {
        return bankCustomerId;
    }

    public String getExternalAccountId() {
        return externalAccountId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void updateDetails(String displayName, String accountType) {
        this.displayName = displayName;
        this.accountType = accountType;
    }

    public void activate() {
        this.status = "ACTIVE";
    }

    public void freeze() {
        this.status = "INACTIVE";
    }

    public void close() {
        this.status = "CLOSED";
    }

    public void applyCredit(BigDecimal amount) {
        this.currentBalance = currentBalance.add(amount);
        this.availableBalance = availableBalance.add(amount);
    }

    public void applyDebit(BigDecimal amount) {
        this.currentBalance = currentBalance.subtract(amount);
        this.availableBalance = availableBalance.subtract(amount);
    }
}
