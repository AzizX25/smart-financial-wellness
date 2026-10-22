package com.azizx25.mockbank.account.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    List<BankAccount> findByBankCustomerIdOrderByExternalAccountIdAsc(UUID bankCustomerId);

    Optional<BankAccount> findByBankCustomerIdAndExternalAccountId(UUID bankCustomerId, String externalAccountId);

    Optional<BankAccount> findByExternalAccountId(String externalAccountId);

    long countByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT account FROM BankAccount account WHERE account.externalAccountId = :accountId")
    Optional<BankAccount> findByExternalAccountIdForUpdate(@Param("accountId") String accountId);

    @Query("""
            SELECT account FROM BankAccount account
            WHERE (:customerId IS NULL OR account.bankCustomerId = :customerId)
              AND (:status IS NULL OR account.status = :status)
              AND (:accountType IS NULL OR account.accountType = :accountType)
            """)
    Page<BankAccount> search(
            UUID customerId,
            String status,
            String accountType,
            Pageable pageable);
}
