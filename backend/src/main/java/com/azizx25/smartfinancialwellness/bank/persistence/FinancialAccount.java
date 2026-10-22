package com.azizx25.smartfinancialwellness.bank.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "financial_account", schema = "smart_financial_wellness")
public class FinancialAccount {

    public enum Origin {
        MANUAL, BANK_CONNECTION
    }

    public enum Status {
        ACTIVE, ARCHIVED, DISCONNECTED
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_connection_id")
    private BankConnection bankConnection;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_origin", nullable = false, length = 20)
    private Origin accountOrigin;

    @Column(name = "external_account_id", length = 255)
    private String externalAccountId;

    @Column(name = "provider_code", length = 60)
    private String providerCode;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "masked_identifier", length = 80)
    private String maskedIdentifier;

    @Column(name = "account_type", nullable = false, length = 30)
    private String accountType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "current_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentBalance;

    @Column(name = "available_balance", precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "provider_account_status", length = 40)
    private String providerAccountStatus;

    @Column(name = "first_synchronized_at")
    private Instant firstSynchronizedAt;

    @Column(name = "last_synchronized_at")
    private Instant lastSynchronizedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected FinancialAccount() {
    }

    public static FinancialAccount manual(AppUser user, String name, String currency,
            BigDecimal declaredBalance, Instant now) {
        FinancialAccount account = new FinancialAccount();
        account.id = UUID.randomUUID();
        account.user = user;
        account.accountOrigin = Origin.MANUAL;
        account.name = name;
        account.accountType = "OTHER";
        account.currency = currency;
        account.currentBalance = declaredBalance.setScale(4, java.math.RoundingMode.UNNECESSARY);
        account.status = Status.ACTIVE;
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public Origin getAccountOrigin() { return accountOrigin; }

    public FinancialAccount(
            AppUser user,
            BankConnection bankConnection,
            ExternalBankAccount externalAccount,
            Instant now) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.bankConnection = bankConnection;
        this.accountOrigin = Origin.BANK_CONNECTION;
        this.externalAccountId = externalAccount.externalAccountId();
        this.providerCode = bankConnection.getProviderCode();
        this.currentBalance = BigDecimal.ZERO.setScale(4);
        this.createdAt = now;
        this.updatedAt = now;
        refreshMetadata(externalAccount);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return user.getId();
    }

    public BankConnection getBankConnection() {
        return bankConnection;
    }

    public String getExternalAccountId() {
        return externalAccountId;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getName() {
        return name;
    }

    public String getMaskedIdentifier() {
        return maskedIdentifier;
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

    public String getProviderAccountStatus() {
        return providerAccountStatus;
    }

    public Instant getFirstSynchronizedAt() {
        return firstSynchronizedAt;
    }

    public Instant getLastSynchronizedAt() {
        return lastSynchronizedAt;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void refreshMetadata(ExternalBankAccount account) {
        this.name = account.displayName();
        this.maskedIdentifier = account.maskedAccountReference();
        this.accountType = account.accountType();
        this.currency = account.currency();
        this.providerAccountStatus = account.status();
        this.status = "ACTIVE".equals(account.status()) ? Status.ACTIVE : Status.DISCONNECTED;
    }

    public void updateBalance(BigDecimal currentBalance, BigDecimal availableBalance) {
        this.currentBalance = currentBalance;
        this.availableBalance = availableBalance;
    }

    public void synchronize(
            BankConnection connection,
            ExternalBankAccount account,
            BigDecimal synchronizedCurrentBalance,
            BigDecimal synchronizedAvailableBalance,
            Instant now) {
        refreshConnection(connection, account);
        updateBalance(synchronizedCurrentBalance, synchronizedAvailableBalance);
        if (firstSynchronizedAt == null) {
            firstSynchronizedAt = now;
        }
        lastSynchronizedAt = now;
    }

    public void refreshConnection(BankConnection connection, ExternalBankAccount account) {
        this.bankConnection = connection;
        this.providerCode = connection.getProviderCode();
        this.externalAccountId = account.externalAccountId();
        refreshMetadata(account);
    }

    public void disconnect() {
        this.status = Status.DISCONNECTED;
    }
}
