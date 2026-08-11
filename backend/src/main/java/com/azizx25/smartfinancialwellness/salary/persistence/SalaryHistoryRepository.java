package com.azizx25.smartfinancialwellness.salary.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SalaryHistoryRepository extends Repository<TransactionRecord, UUID> {

    @Query(value = """
            SELECT CAST(DATE_TRUNC('month', transaction.booking_date) AS DATE) AS "spendingMonth",
                   category.id AS "categoryId",
                   SUM(transaction.amount) AS "totalAmount"
              FROM smart_financial_wellness.transaction_record transaction
              JOIN smart_financial_wellness.category category ON category.id = transaction.category_id
             WHERE transaction.user_id = :userId
               AND transaction.currency = :currency
               AND transaction.booking_date >= :fromDate
               AND transaction.booking_date < :toDateExclusive
               AND transaction.direction = 'DEBIT'
               AND category.active = TRUE
               AND category.nature = 'EXPENSE'
               AND (category.user_id IS NULL OR category.user_id = :userId)
               AND (
                    transaction.source_type IN ('MANUAL', 'FILE_IMPORT')
                    OR (transaction.source_type = 'BANK_SYNC' AND transaction.provider_status = 'POSTED')
               )
               AND NOT EXISTS (
                    SELECT 1
                      FROM smart_financial_wellness.internal_transfer transfer
                     WHERE transfer.outgoing_transaction_id = transaction.id
                        OR transfer.incoming_transaction_id = transaction.id
               )
             GROUP BY DATE_TRUNC('month', transaction.booking_date), category.id
             ORDER BY DATE_TRUNC('month', transaction.booking_date), category.id
            """, nativeQuery = true)
    List<HistoricalSpendingRow> summarizeEligibleExpenseSpending(
            @Param("userId") UUID userId,
            @Param("currency") String currency,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDateExclusive") LocalDate toDateExclusive);

    interface HistoricalSpendingRow {
        LocalDate getSpendingMonth();
        UUID getCategoryId();
        BigDecimal getTotalAmount();
    }
}
