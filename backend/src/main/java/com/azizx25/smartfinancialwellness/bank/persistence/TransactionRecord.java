package com.azizx25.smartfinancialwellness.bank.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

import com.azizx25.smartfinancialwellness.category.persistence.Category;
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
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "transaction_record", schema = "smart_financial_wellness")
@SQLRestriction("deleted_at IS NULL")
public class TransactionRecord {

    public static final UUID UNCATEGORIZED_CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public enum SourceType {
        MANUAL, FILE_IMPORT, BANK_SYNC
    }

    public enum Direction {
        DEBIT, CREDIT
    }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "financial_account_id", nullable = false)
    private FinancialAccount financialAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "applied_category_prediction_id")
    private UUID appliedCategoryPredictionId;

    @Column(name = "matched_categorization_rule_id")
    private UUID matchedCategorizationRuleId;

    @Column(name = "categorized_at")
    private Instant categorizedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "import_job_id")
    private UUID importJobId;

    @Column(name = "source_file_id")
    private UUID sourceFileId;

    @Column(name = "source_row_number")
    private Integer sourceRowNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String fingerprint;

    @Column(name = "client_idempotency_key", length = 160)
    private String clientIdempotencyKey;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "local_override", nullable = false)
    private boolean localOverride;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_details", columnDefinition = "jsonb")
    private Map<String, String> sourceDetails;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_job_id")
    private SyncJob syncJob;

    @Column(name = "external_transaction_id", length = 255)
    private String externalTransactionId;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "raw_description", nullable = false, length = 1000)
    private String rawDescription;

    @Column(name = "normalized_description", nullable = false, length = 1000)
    private String normalizedDescription;

    @Column(name = "recognized_merchant", length = 255)
    private String recognizedMerchant;

    @Column(name = "category_source", nullable = false, length = 30)
    private String categorySource;

    @Column(name = "provider_status", length = 40)
    private String providerStatus;

    @Column(name = "first_imported_at", updatable = false)
    private Instant firstImportedAt;

    @Column(name = "last_synchronized_at")
    private Instant lastSynchronizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected TransactionRecord() {
    }

    public static TransactionRecord manual(AppUser user, FinancialAccount account, Category initialCategory,
            String idempotencyKey, LocalDate date, BigDecimal amount, Direction direction,
            String description, String normalized, Instant now) {
        TransactionRecord transaction = new TransactionRecord();
        transaction.id = UUID.randomUUID();
        transaction.user = user;
        transaction.financialAccount = account;
        transaction.category = initialCategory;
        transaction.sourceType = SourceType.MANUAL;
        transaction.clientIdempotencyKey = idempotencyKey;
        transaction.currency = account.getCurrency();
        transaction.categorySource = "UNCATEGORIZED";
        transaction.createdAt = now;
        transaction.updateManual(date, amount, direction, description, normalized, now);
        return transaction;
    }

    public void updateManual(LocalDate date, BigDecimal amount, Direction direction,
            String description, String normalized, Instant now) {
        if (sourceType != SourceType.MANUAL) throw new IllegalStateException("Manual transaction required");
        this.bookingDate = date;
        this.amount = amount;
        this.direction = direction;
        this.rawDescription = description;
        this.normalizedDescription = normalized;
        this.recognizedMerchant = null;
        this.updatedAt = now;
    }

    public void updateLocally(LocalDate date, BigDecimal amount, Direction direction,
            String description, String normalized, Instant now) {
        if (sourceType == SourceType.MANUAL) {
            updateManual(date, amount, direction, description, normalized, now);
            return;
        }
        if (sourceDetails == null) {
            sourceDetails = details(bookingDate, valueDate, this.amount, this.direction,
                    currency, rawDescription, recognizedMerchant, providerStatus);
        }
        localOverride = true;
        bookingDate = date;
        this.amount = amount;
        this.direction = direction;
        rawDescription = description;
        normalizedDescription = normalized;
        recognizedMerchant = null;
        updatedAt = now;
    }

    private static Map<String, String> details(LocalDate date, LocalDate valueDate, BigDecimal amount,
            Direction direction, String currency, String description, String merchant, String status) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("bookingDate", date.toString());
        if (valueDate != null) result.put("valueDate", valueDate.toString());
        result.put("amount", amount.toPlainString());
        result.put("direction", direction.name());
        result.put("currency", currency);
        result.put("description", description);
        if (merchant != null) result.put("merchant", merchant);
        if (status != null) result.put("status", status);
        return result;
    }

    public int getVersion() { return version; }
    public boolean isLocalOverride() { return localOverride; }
    public boolean isCategoryApproved() {
        // SQL integrity requires accepted predictions for ML/LLM/USER_MEMORY assignments.
        return "USER".equals(categorySource) || "ML".equals(categorySource)
                || "LLM".equals(categorySource) || "USER_MEMORY".equals(categorySource);
    }

    public static TransactionRecord imported(AppUser user, FinancialAccount account, Category category,
            UUID jobId, UUID fileId, int rowNumber, LocalDate date, BigDecimal amount,
            Direction direction, String description, String normalized, String fingerprint, Instant now) {
        TransactionRecord transaction = new TransactionRecord();
        transaction.id = UUID.randomUUID();
        transaction.user = user;
        transaction.financialAccount = account;
        transaction.category = category;
        transaction.sourceType = SourceType.FILE_IMPORT;
        transaction.importJobId = jobId;
        transaction.sourceFileId = fileId;
        transaction.sourceRowNumber = rowNumber;
        transaction.fingerprint = fingerprint;
        transaction.bookingDate = date;
        transaction.amount = amount;
        transaction.direction = direction;
        transaction.currency = account.getCurrency();
        transaction.rawDescription = description;
        transaction.normalizedDescription = normalized;
        transaction.categorySource = "UNCATEGORIZED";
        transaction.createdAt = now;
        transaction.updatedAt = now;
        return transaction;
    }

    public TransactionRecord(
            AppUser user,
            FinancialAccount account,
            Category initialCategory,
            SyncJob job,
            String providerTransactionId,
            LocalDate transactionBookingDate,
            LocalDate transactionValueDate,
            BigDecimal transactionAmount,
            Direction transactionDirection,
            String transactionCurrency,
            String description,
            String normalized,
            String counterpartyName,
            String status,
            Instant now) {
        id = UUID.randomUUID();
        this.user = user;
        financialAccount = account;
        category = initialCategory;
        sourceType = SourceType.BANK_SYNC;
        syncJob = job;
        externalTransactionId = providerTransactionId;
        firstImportedAt = now;
        createdAt = now;
        updatedAt = now;
        categorySource = "UNCATEGORIZED";
        synchronize(job, transactionBookingDate, transactionValueDate, transactionAmount,
                transactionDirection, transactionCurrency, description, normalized,
                counterpartyName, status, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return financialAccount.getId();
    }

    public UUID getUserId() { return user.getId(); }
    public AppUser getUser() { return user; }
    public Category getCategory() { return category; }
    public String getCategorySource() { return categorySource; }
    public SourceType getSourceType() { return sourceType; }
    public String getNormalizedDescription() { return normalizedDescription; }
    public Instant getCategorizedAt() { return categorizedAt; }

    public String getExternalTransactionId() {
        return externalTransactionId;
    }

    public LocalDate getBookingDate() {
        return bookingDate;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return rawDescription;
    }

    public String getRecognizedMerchant() {
        return recognizedMerchant;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public Instant getFirstImportedAt() {
        return firstImportedAt;
    }

    public Instant getLastSynchronizedAt() {
        return lastSynchronizedAt;
    }

    public void assignManually(Category target, Instant now) {
        category = target;
        categorySource = "USER";
        appliedCategoryPredictionId = null;
        matchedCategorizationRuleId = null;
        categorizedAt = now;
    }

    public void removeCategory(Category uncategorized) {
        category = uncategorized;
        categorySource = "UNCATEGORIZED";
        appliedCategoryPredictionId = null;
        matchedCategorizationRuleId = null;
        categorizedAt = null;
    }

    public void assignByRule(Category target, UUID predictionId, UUID ruleId, Instant now) {
        if (!"UNCATEGORIZED".equals(categorySource)) {
            return;
        }
        category = target;
        categorySource = "RULES";
        appliedCategoryPredictionId = predictionId;
        matchedCategorizationRuleId = ruleId;
        categorizedAt = now;
    }

    public void assignByMl(Category target, UUID predictionId, Instant now) {
        if (!"UNCATEGORIZED".equals(categorySource)) {
            return;
        }
        category = target;
        categorySource = "ML";
        appliedCategoryPredictionId = predictionId;
        matchedCategorizationRuleId = null;
        categorizedAt = now;
    }

    public void assignByLlm(Category target, UUID predictionId, Instant now) {
        if (!"UNCATEGORIZED".equals(categorySource)) {
            return;
        }
        category = target;
        categorySource = "LLM";
        appliedCategoryPredictionId = predictionId;
        matchedCategorizationRuleId = null;
        categorizedAt = now;
    }

    public void assignByUserMemory(Category target, UUID predictionId, Instant now) {
        if (!"UNCATEGORIZED".equals(categorySource)) {
            return;
        }
        category = target;
        categorySource = "USER_MEMORY";
        appliedCategoryPredictionId = predictionId;
        matchedCategorizationRuleId = null;
        categorizedAt = now;
    }

    public void synchronize(
            SyncJob job,
            LocalDate transactionBookingDate,
            LocalDate transactionValueDate,
            BigDecimal transactionAmount,
            Direction transactionDirection,
            String transactionCurrency,
            String description,
            String normalized,
            String counterpartyName,
            String status,
            Instant now) {
        syncJob = job;
        sourceDetails = details(transactionBookingDate, transactionValueDate, transactionAmount,
                transactionDirection, transactionCurrency, description, counterpartyName, status);
        providerStatus = status;
        lastSynchronizedAt = now;
        if (localOverride) return;
        bookingDate = transactionBookingDate;
        valueDate = transactionValueDate;
        amount = transactionAmount;
        direction = transactionDirection;
        currency = transactionCurrency;
        rawDescription = description;
        normalizedDescription = normalized;
        recognizedMerchant = counterpartyName;
        providerStatus = status;
        lastSynchronizedAt = now;
    }

}
