package com.azizx25.mockbank.transaction.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    private UUID id;

    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

    @Column(name = "external_transaction_id", nullable = false, length = 120)
    private String externalTransactionId;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String direction;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "counterparty_name", length = 160)
    private String counterpartyName;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected BankTransaction() {
    }

    public BankTransaction(
            UUID bankAccountId,
            String externalTransactionId,
            LocalDate bookingDate,
            BigDecimal amount,
            String direction,
            String currency,
            String description,
            String counterpartyName,
            OffsetDateTime createdAt) {
        this(
                bankAccountId,
                externalTransactionId,
                bookingDate,
                bookingDate,
                amount,
                direction,
                currency,
                description,
                counterpartyName,
                "POSTED",
                createdAt);
    }

    public BankTransaction(
            UUID bankAccountId,
            String externalTransactionId,
            LocalDate bookingDate,
            LocalDate valueDate,
            BigDecimal amount,
            String direction,
            String currency,
            String description,
            String counterpartyName,
            String status,
            OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.bankAccountId = bankAccountId;
        this.externalTransactionId = externalTransactionId;
        this.bookingDate = bookingDate;
        this.valueDate = valueDate;
        this.amount = amount;
        this.direction = direction;
        this.currency = currency;
        this.description = description;
        this.counterpartyName = counterpartyName;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBankAccountId() {
        return bankAccountId;
    }

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

    public String getDirection() {
        return direction;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public String getCounterpartyName() {
        return counterpartyName;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
