package com.azizx25.smartfinancialwellness.forecast.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccount;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ForecastFinancialDataRepository extends Repository<FinancialAccount, UUID> {

    @Query("""
            select account from FinancialAccount account
            where account.user.id = :userId
              and account.currency = :currency
              and account.status = :status
            order by account.id
            """)
    List<FinancialAccount> findEligibleAccounts(
            @Param("userId") UUID userId,
            @Param("currency") String currency,
            @Param("status") FinancialAccount.Status status);

    @Query(value = """
            SELECT transaction.booking_date AS "bookingDate",
                   transaction.amount AS "amount",
                   transaction.direction AS "direction"
              FROM smart_financial_wellness.transaction_record transaction
             WHERE transaction.user_id = :userId
               AND transaction.financial_account_id IN (:accountIds)
               AND transaction.currency = :currency
               AND transaction.booking_date >= :fromDate
               AND transaction.booking_date < :toDateExclusive
               AND (
                    (transaction.source_type IN ('MANUAL', 'FILE_IMPORT')
                        AND transaction.provider_status IS NULL)
                    OR (transaction.source_type = 'BANK_SYNC'
                        AND transaction.provider_status = 'POSTED')
               )
               AND NOT EXISTS (
                    SELECT 1
                      FROM smart_financial_wellness.internal_transfer transfer
                      JOIN smart_financial_wellness.transaction_record outgoing
                        ON outgoing.id = transfer.outgoing_transaction_id
                      JOIN smart_financial_wellness.transaction_record incoming
                        ON incoming.id = transfer.incoming_transaction_id
                     WHERE (transfer.outgoing_transaction_id = transaction.id
                            OR transfer.incoming_transaction_id = transaction.id)
                       AND outgoing.financial_account_id IN (:accountIds)
                       AND incoming.financial_account_id IN (:accountIds)
               )
             ORDER BY transaction.booking_date, transaction.id
            """, nativeQuery = true)
    List<HistoricalCashFlowRow> findEligibleCashFlows(
            @Param("userId") UUID userId,
            @Param("accountIds") Collection<UUID> accountIds,
            @Param("currency") String currency,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDateExclusive") LocalDate toDateExclusive);

    interface HistoricalCashFlowRow {
        LocalDate getBookingDate();

        BigDecimal getAmount();

        String getDirection();
    }
}
