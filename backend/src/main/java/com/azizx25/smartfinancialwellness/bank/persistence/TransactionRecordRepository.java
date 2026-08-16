package com.azizx25.smartfinancialwellness.bank.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, UUID> {

    List<TransactionRecord> findByFinancialAccount_IdAndExternalTransactionIdIn(
            UUID accountId,
            Collection<String> providerTransactionIds);

    @Query("""
            select transaction from TransactionRecord transaction
            where transaction.user.id = :userId
              and transaction.financialAccount.id = coalesce(:accountId, transaction.financialAccount.id)
              and transaction.bookingDate >= coalesce(:fromDate, transaction.bookingDate)
              and transaction.bookingDate <= coalesce(:toDate, transaction.bookingDate)
              and transaction.direction = coalesce(:direction, transaction.direction)
              and transaction.category.id = coalesce(:categoryId, transaction.category.id)
              and (:uncategorizedOnly = false or transaction.category.id = :uncategorizedCategoryId)
              and locate(
                    lower(:search),
                    lower(concat(
                        coalesce(transaction.rawDescription, ''),
                        concat(' ', coalesce(transaction.recognizedMerchant, ''))
                    ))) > 0
            """)
    Page<TransactionRecord> findLocalTransactions(
            @Param("userId") UUID userId,
            @Param("accountId") UUID accountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("direction") TransactionRecord.Direction direction,
            @Param("categoryId") UUID categoryId,
            @Param("uncategorizedOnly") boolean uncategorizedOnly,
            @Param("uncategorizedCategoryId") UUID uncategorizedCategoryId,
            @Param("search") String search,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select transaction from TransactionRecord transaction
            where transaction.id = :transactionId and transaction.user.id = :userId
            """)
    java.util.Optional<TransactionRecord> findOwnedByIdForUpdate(
            @Param("transactionId") UUID transactionId,
            @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select transaction from TransactionRecord transaction
            where transaction.user.id = :userId
              and transaction.category.id = :uncategorizedCategoryId
              and transaction.categorySource = 'UNCATEGORIZED'
            order by transaction.bookingDate, transaction.id
            """)
    List<TransactionRecord> findUncategorizedForUpdate(
            @Param("userId") UUID userId,
            @Param("uncategorizedCategoryId") UUID uncategorizedCategoryId);

    long countByUser_IdAndCategory_Id(UUID userId, UUID categoryId);

    @Query(value = """
            SELECT transaction.currency AS currency,
                   COALESCE(SUM(transaction.amount)
                       FILTER (WHERE transaction.direction = 'CREDIT'), 0) AS income,
                   COALESCE(SUM(transaction.amount)
                       FILTER (WHERE transaction.direction = 'DEBIT'), 0) AS expenses
              FROM smart_financial_wellness.transaction_record transaction
             WHERE transaction.user_id = :userId
               AND transaction.booking_date >= :fromDate
               AND transaction.booking_date < :toDateExclusive
               AND NOT EXISTS (
                   SELECT 1
                     FROM smart_financial_wellness.internal_transfer transfer
                    WHERE transfer.outgoing_transaction_id = transaction.id
                       OR transfer.incoming_transaction_id = transaction.id
               )
             GROUP BY transaction.currency
             ORDER BY transaction.currency
            """, nativeQuery = true)
    List<MonthlyCashFlowTotal> summarizeMonthlyCashFlow(
            @Param("userId") UUID userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDateExclusive") LocalDate toDateExclusive);

    interface MonthlyCashFlowTotal {

        String getCurrency();

        BigDecimal getIncome();

        BigDecimal getExpenses();
    }
}
