package com.azizx25.smartfinancialwellness.bank.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, UUID> {

    Optional<FinancialAccount> findByBankConnection_IdAndExternalAccountId(
            UUID bankConnectionId,
            String externalAccountId);

    Optional<FinancialAccount> findTopByUser_IdAndProviderCodeAndExternalAccountIdOrderByUpdatedAtDesc(
            UUID userId,
            String providerCode,
            String externalAccountId);

    Optional<FinancialAccount> findByUser_IdAndProviderCodeAndExternalAccountIdAndFirstSynchronizedAtIsNotNull(
            UUID userId,
            String providerCode,
            String externalAccountId);

    Optional<FinancialAccount> findByIdAndUser_Id(UUID accountId, UUID userId);

    Optional<FinancialAccount> findByIdAndUser_IdAndFirstSynchronizedAtIsNotNull(
            UUID accountId,
            UUID userId);

    List<FinancialAccount> findByBankConnection_IdAndUser_Id(UUID bankConnectionId, UUID userId);

    List<FinancialAccount> findByUser_IdAndFirstSynchronizedAtIsNotNullOrderByNameAscIdAsc(UUID userId);

    @Query("""
            select account.currency as currency,
                   sum(account.currentBalance) as totalBalance,
                   count(account) as accountCount
              from FinancialAccount account
             where account.user.id = :userId
               and account.firstSynchronizedAt is not null
             group by account.currency
             order by account.currency
            """)
    List<AccountBalanceTotal> summarizeBalances(@Param("userId") UUID userId);

    interface AccountBalanceTotal {

        String getCurrency();

        BigDecimal getTotalBalance();

        long getAccountCount();
    }
}
