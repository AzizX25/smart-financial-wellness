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

    // Include tombstones so a retried create never resurrects a removed entry.
    @Query(value = "SELECT id FROM smart_financial_wellness.transaction_record "
            + "WHERE user_id = :userId AND source_type = 'MANUAL' AND client_idempotency_key = :key",
            nativeQuery = true)
    java.util.Optional<UUID> findManualIdempotencyId(@Param("userId") UUID userId, @Param("key") String key);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM smart_financial_wellness.goal_contribution WHERE transaction_id = :id)
                OR EXISTS (SELECT 1 FROM smart_financial_wellness.internal_transfer
                           WHERE outgoing_transaction_id = :id OR incoming_transaction_id = :id)
            """, nativeQuery = true)
    boolean hasProtectedLinks(@Param("id") UUID id);

    List<TransactionRecord> findByFinancialAccount_IdAndExternalTransactionIdIn(
            UUID accountId,
            Collection<String> providerTransactionIds);

    // Lock retained source identities, including tombstones, before replaying a provider batch.
    @Query(value = """
            SELECT external_transaction_id AS externalId, deleted_at IS NOT NULL AS removed
              FROM smart_financial_wellness.transaction_record
             WHERE financial_account_id = :accountId AND source_type = 'BANK_SYNC'
               AND external_transaction_id IN (:ids)
             ORDER BY id FOR UPDATE
            """, nativeQuery = true)
    List<BankReplayIdentity> lockBankReplayIdentities(@Param("accountId") UUID accountId,
            @Param("ids") Collection<String> ids);

    interface BankReplayIdentity {
        String getExternalId();
        boolean getRemoved();
    }

    @Query("""
            select transaction from TransactionRecord transaction
            where transaction.user.id = :userId
              and transaction.financialAccount.id = coalesce(:accountId, transaction.financialAccount.id)
              and transaction.bookingDate >= coalesce(:fromDate, transaction.bookingDate)
              and transaction.bookingDate <= coalesce(:toDate, transaction.bookingDate)
              and transaction.direction = coalesce(:direction, transaction.direction)
              and transaction.category.id = coalesce(:categoryId, transaction.category.id)
              and (:uncategorizedOnly = false or transaction.category.id = :uncategorizedCategoryId)
              and (:approved is null
                   or (:approved = true and transaction.categorySource in ('USER', 'ML', 'LLM', 'USER_MEMORY'))
                   or (:approved = false and transaction.categorySource not in ('USER', 'ML', 'LLM', 'USER_MEMORY')))
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
            @Param("approved") Boolean approved,
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

    @Query("""
            select transaction from TransactionRecord transaction
            where transaction.user.id = :userId
              and transaction.category.id = :uncategorizedCategoryId
              and transaction.categorySource = 'UNCATEGORIZED'
            order by transaction.bookingDate, transaction.id
            """)
    Page<TransactionRecord> findUncategorizedForReview(
            @Param("userId") UUID userId,
            @Param("uncategorizedCategoryId") UUID uncategorizedCategoryId,
            Pageable pageable);

    @Query(value = """
            SELECT DISTINCT linked.transaction_id
              FROM (
                    SELECT outgoing_transaction_id AS transaction_id
                      FROM smart_financial_wellness.internal_transfer
                     WHERE confirmed_at IS NOT NULL
                    UNION ALL
                    SELECT incoming_transaction_id AS transaction_id
                      FROM smart_financial_wellness.internal_transfer
                     WHERE confirmed_at IS NOT NULL
              ) linked
             WHERE linked.transaction_id IN (:transactionIds)
            """, nativeQuery = true)
    List<UUID> findConfirmedInternalTransferTransactionIds(
            @Param("transactionIds") Collection<UUID> transactionIds);

    @Query(value = """
            SELECT transaction.*
              FROM smart_financial_wellness.transaction_record transaction
             WHERE transaction.user_id = :userId
               AND transaction.deleted_at IS NULL
               AND transaction.category_id = :uncategorizedCategoryId
               AND transaction.category_source = 'UNCATEGORIZED'
               AND transaction.direction = 'DEBIT'
               AND ((transaction.source_type IN ('MANUAL', 'FILE_IMPORT') AND transaction.provider_status IS NULL)
                    OR (transaction.source_type = 'BANK_SYNC' AND transaction.provider_status = 'POSTED'))
               AND NOT EXISTS (
                   SELECT 1 FROM smart_financial_wellness.internal_transfer transfer
                    WHERE transfer.confirmed_at IS NOT NULL
                      AND (transfer.outgoing_transaction_id = transaction.id
                           OR transfer.incoming_transaction_id = transaction.id)
               )
             ORDER BY transaction.booking_date, transaction.id
            """, nativeQuery = true)
    List<TransactionRecord> findMerchantRecognitionCandidates(
            @Param("userId") UUID userId,
            @Param("uncategorizedCategoryId") UUID uncategorizedCategoryId,
            Pageable pageable);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                  FROM smart_financial_wellness.transaction_record transaction
                 WHERE transaction.id = :transactionId
                   AND transaction.deleted_at IS NULL
                   AND transaction.user_id = :userId
                   AND transaction.category_id = :uncategorizedCategoryId
                   AND transaction.category_source = 'UNCATEGORIZED'
                   AND transaction.direction = 'DEBIT'
                   AND transaction.currency = 'TND'
                   AND ((transaction.source_type IN ('MANUAL', 'FILE_IMPORT') AND transaction.provider_status IS NULL)
                        OR (transaction.source_type = 'BANK_SYNC' AND transaction.provider_status = 'POSTED'))
                   AND NOT EXISTS (
                       SELECT 1 FROM smart_financial_wellness.internal_transfer transfer
                        WHERE transfer.confirmed_at IS NOT NULL
                          AND (transfer.outgoing_transaction_id = transaction.id
                               OR transfer.incoming_transaction_id = transaction.id)
                   )
            )
            """, nativeQuery = true)
    boolean isMerchantRecognitionEligible(
            @Param("transactionId") UUID transactionId,
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
               AND transaction.deleted_at IS NULL
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

    @Query(value = """
            SELECT transaction.currency AS currency,
                   category.id AS categoryId,
                   category.label AS categoryName,
                   COUNT(*) AS transactionCount,
                   SUM(transaction.amount) AS totalAmount
              FROM smart_financial_wellness.transaction_record transaction
              JOIN smart_financial_wellness.category category
                ON category.id = transaction.category_id
             WHERE transaction.user_id = :userId
               AND transaction.deleted_at IS NULL
               AND transaction.booking_date >= :fromDate
               AND transaction.booking_date < :toDateExclusive
               AND transaction.direction = 'DEBIT'
               AND (category.nature = 'EXPENSE' OR category.id = :uncategorizedCategoryId)
               AND ((transaction.source_type IN ('MANUAL', 'FILE_IMPORT')
                     AND transaction.provider_status IS NULL)
                    OR (transaction.source_type = 'BANK_SYNC'
                        AND transaction.provider_status = 'POSTED'))
               AND NOT EXISTS (
                   SELECT 1
                     FROM smart_financial_wellness.internal_transfer transfer
                    WHERE transfer.confirmed_at IS NOT NULL
                      AND (transfer.outgoing_transaction_id = transaction.id
                           OR transfer.incoming_transaction_id = transaction.id)
               )
             GROUP BY transaction.currency, category.id, category.label
             ORDER BY transaction.currency, SUM(transaction.amount) DESC, category.label, category.id
            """, nativeQuery = true)
    List<MonthlyCategoryExpenseTotal> summarizeMonthlyCategoryExpenses(
            @Param("userId") UUID userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDateExclusive") LocalDate toDateExclusive,
            @Param("uncategorizedCategoryId") UUID uncategorizedCategoryId);

    interface MonthlyCashFlowTotal {

        String getCurrency();

        BigDecimal getIncome();

        BigDecimal getExpenses();
    }

    interface MonthlyCategoryExpenseTotal {

        String getCurrency();

        UUID getCategoryId();

        String getCategoryName();

        long getTransactionCount();

        BigDecimal getTotalAmount();
    }
}
