package com.azizx25.smartfinancialwellness.category.persistence;

import java.time.Instant;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
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
@Table(name = "user_merchant_memory", schema = "smart_financial_wellness")
public class UserMerchantMemory {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "normalized_merchant_key", nullable = false, length = 160)
    private String normalizedMerchantKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionRecord.Direction direction;

    @Column(nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected UserMerchantMemory() {
    }

    public UserMerchantMemory(
            AppUser owner, String merchantKey, TransactionRecord.Direction transactionDirection,
            String transactionCurrency, Category learnedCategory, Instant now) {
        id = UUID.randomUUID();
        user = owner;
        normalizedMerchantKey = merchantKey;
        direction = transactionDirection;
        currency = transactionCurrency;
        category = learnedCategory;
        createdAt = now;
        updatedAt = now;
    }

    public void replaceCategory(Category replacement) {
        category = replacement;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return user.getId(); }
    public String getNormalizedMerchantKey() { return normalizedMerchantKey; }
    public TransactionRecord.Direction getDirection() { return direction; }
    public String getCurrency() { return currency; }
    public Category getCategory() { return category; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
