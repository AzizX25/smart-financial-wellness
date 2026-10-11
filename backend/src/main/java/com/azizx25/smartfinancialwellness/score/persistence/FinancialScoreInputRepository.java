package com.azizx25.smartfinancialwellness.score.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface FinancialScoreInputRepository extends Repository<TransactionRecord, UUID> {

    @Query(value = """
            SELECT transaction.id AS transactionId,
                   transaction.booking_date AS bookingDate,
                   transaction.amount AS amount,
                   transaction.direction AS direction,
                   transaction.source_type AS sourceType,
                   COALESCE(transaction.provider_status, '') AS providerStatus,
                   category.code AS categoryCode
              FROM smart_financial_wellness.transaction_record transaction
              JOIN smart_financial_wellness.category category ON category.id = transaction.category_id
             WHERE transaction.user_id = :userId
               AND transaction.currency = :currency
               AND transaction.booking_date >= :fromDate
               AND transaction.booking_date < :toDateExclusive
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
             ORDER BY transaction.booking_date, transaction.id
            """, nativeQuery = true)
    List<EligibleTransactionRow> findEligibleTransactions(
            @Param("userId") UUID userId,
            @Param("currency") String currency,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDateExclusive") LocalDate toDateExclusive);

    @Query(value = """
            SELECT plan.id AS planId,
                   plan.period_month AS periodMonth,
                   COALESCE(SUM(COALESCE(allocation.confirmed_amount,
                                        allocation.recommended_amount)), 0) AS plannedExpenseBudget
              FROM smart_financial_wellness.monthly_plan plan
              LEFT JOIN smart_financial_wellness.budget_allocation allocation
                ON allocation.monthly_plan_id = plan.id
             WHERE plan.user_id = :userId
               AND plan.currency = :currency
               AND plan.period_month >= :fromDate
               AND plan.period_month < :toDateExclusive
             GROUP BY plan.id, plan.period_month
             ORDER BY plan.period_month, plan.id
            """, nativeQuery = true)
    List<CompletedPlanRow> findCompletedPlanBudgets(
            @Param("userId") UUID userId,
            @Param("currency") String currency,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDateExclusive") LocalDate toDateExclusive);

    interface EligibleTransactionRow {
        UUID getTransactionId();
        LocalDate getBookingDate();
        BigDecimal getAmount();
        String getDirection();
        String getSourceType();
        String getProviderStatus();
        String getCategoryCode();
    }

    interface CompletedPlanRow {
        UUID getPlanId();
        LocalDate getPeriodMonth();
        BigDecimal getPlannedExpenseBudget();
    }
}
